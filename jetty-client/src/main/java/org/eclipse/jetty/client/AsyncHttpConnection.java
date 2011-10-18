package org.eclipse.jetty.client;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslSelectChannelEndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public class AsyncHttpConnection extends AbstractHttpConnection implements AsyncConnection
{
    private static final Logger LOG = Log.getLogger(AsyncHttpConnection.class);
    
    private Buffer _requestContentChunk;
    private boolean _requestComplete;
    private int _status;
    
    AsyncHttpConnection(Buffers requestBuffers, Buffers responseBuffers, EndPoint endp)
    {
        super(requestBuffers,responseBuffers,endp);
    }

    protected void reset(boolean returnBuffers) throws IOException
    {
        _requestComplete = false;
        super.reset(returnBuffers);
    }
    
    public Connection handle() throws IOException
    {
        try
        {
            int no_progress = 0;

            boolean failed = false;
            while (_endp.isBufferingInput() || _endp.isOpen())
            {
                synchronized (this)
                {
                    while (_exchange == null)
                    {
                        if (_endp.isBlocking())
                        {
                            try
                            {
                                this.wait();
                            }
                            catch (InterruptedException e)
                            {
                                throw new InterruptedIOException();
                            }
                        }
                        else
                        {
                            long filled = _parser.fill();
                            if (filled < 0)
                            {
                                close();
                            }
                            else
                            {
                                // Hopefully just space?
                                _parser.skipCRLF();
                                if (_parser.isMoreInBuffer())
                                {
                                    LOG.warn("Unexpected data received but no request sent");
                                    close();
                                }
                            }
                            return this;
                        }
                    }
                }

                try
                {
                    if (_exchange.getStatus() == HttpExchange.STATUS_WAITING_FOR_COMMIT)
                    {
                        no_progress = 0;
                        commitRequest();
                    }

                    long io = 0;
                    _endp.flush();

                    if (_generator.isComplete())
                    {
                        if (!_requestComplete)
                        {
                            _requestComplete = true;
                            _exchange.getEventListener().onRequestComplete();
                        }
                    }
                    else
                    {
                        // Write as much of the request as possible
                        synchronized (this)
                        {
                            if (_exchange == null)
                                continue;
                        }

                        long flushed = _generator.flushBuffer();
                        io += flushed;

                        if (!_generator.isComplete())
                        {
                            if (_exchange!=null)
                            {
                                InputStream in = _exchange.getRequestContentSource();
                                if (in != null)
                                {
                                    if (_requestContentChunk == null || _requestContentChunk.length() == 0)
                                    {
                                        _requestContentChunk = _exchange.getRequestContentChunk();

                                        if (_requestContentChunk != null)
                                            _generator.addContent(_requestContentChunk,false);
                                        else
                                            _generator.complete();

                                        flushed = _generator.flushBuffer();
                                        io += flushed;
                                    }
                                }
                                else
                                    _generator.complete();
                            }
                            else
                                _generator.complete();
                        }
                    }

                    if (_generator.isComplete() && !_requestComplete)
                    {
                        _requestComplete = true;
                        _exchange.getEventListener().onRequestComplete();
                    }

                    // If we are not ended then parse available
                    if (!_parser.isComplete() && (_generator.isComplete() || _generator.isCommitted() && !_endp.isBlocking()))
                    {
                        if (_parser.parseAvailable())
                            io++;

                        if (_parser.isIdle() && (_endp.isInputShutdown() || !_endp.isOpen()))
                            throw new EOFException();
                    }

                    if (io > 0)
                        no_progress = 0;
                    else if (no_progress++ >= 1 && !_endp.isBlocking())
                    {
                        // SSL may need an extra flush as it may have made "no progress" while actually doing a handshake.
                        if (_endp instanceof SslSelectChannelEndPoint && !_generator.isComplete() && !_generator.isEmpty())
                        {
                            long flushed = _generator.flushBuffer();
                            if (flushed>0)
                                continue;
                        }
                        return this;
                    }
                }
                catch (Throwable e)
                {
                    LOG.debug("Failure on " + _exchange, e);

                    if (e instanceof ThreadDeath)
                        throw (ThreadDeath)e;

                    failed = true;

                    synchronized (this)
                    {
                        if (_exchange != null)
                        {
                            // Cancelling the exchange causes an exception as we close the connection,
                            // but we don't report it as it is normal cancelling operation
                            if (_exchange.getStatus() != HttpExchange.STATUS_CANCELLING &&
                                    _exchange.getStatus() != HttpExchange.STATUS_CANCELLED)
                            {
                                _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                                _exchange.getEventListener().onException(e);
                            }
                        }
                        else
                        {
                            if (e instanceof IOException)
                                throw (IOException)e;

                            if (e instanceof Error)
                                throw (Error)e;

                            if (e instanceof RuntimeException)
                                throw (RuntimeException)e;

                            throw new RuntimeException(e);
                        }
                    }
                }
                finally
                {
                    boolean complete = false;
                    boolean close = failed; // always close the connection on error
                    if (!failed)
                    {
                        // are we complete?
                        if (_generator.isComplete())
                        {
                            if (!_requestComplete)
                            {
                                _requestComplete = true;
                                _exchange.getEventListener().onRequestComplete();
                            }

                            // we need to return the HttpConnection to a state that
                            // it can be reused or closed out
                            if (_parser.isComplete())
                            {
                                _exchange.cancelTimeout(_destination.getHttpClient());
                                complete = true;
                            }
                        }

                        // if the endpoint is closed, but the parser incomplete
                        if (!_endp.isOpen() && !(_parser.isComplete()||_parser.isIdle()))
                        {
                            // we wont be called again so let the parser see the close
                            complete=true;
                            _parser.parseAvailable();
                            // TODO should not need this
                            if (!(_parser.isComplete()||_parser.isIdle()))
                            {
                                LOG.warn("Incomplete {} {}",_parser,_endp);
                                if (_exchange!=null && !_exchange.isDone())
                                {
                                    _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                                    _exchange.getEventListener().onException(new EOFException("Incomplete"));
                                }
                            }
                        }
                    }

                    if (_endp.isInputShutdown() && !_parser.isComplete() && !_parser.isIdle())
                    {
                        if (_exchange!=null && !_exchange.isDone())
                        {
                            _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                            _exchange.getEventListener().onException(new EOFException("Incomplete"));
                        }
                        _endp.close();
                    }

                    if (complete || failed)
                    {
                        synchronized (this)
                        {
                            if (!close)
                                close = shouldClose();

                            reset(true);

                            no_progress = 0;
                            if (_exchange != null)
                            {
                                HttpExchange exchange=_exchange;
                                _exchange = null;

                                // Reset the maxIdleTime because it may have been changed
                                if (!close)
                                    _endp.setMaxIdleTime((int)_destination.getHttpClient().getIdleTimeout());

                                if (_status==HttpStatus.SWITCHING_PROTOCOLS_101)
                                {
                                    Connection switched=exchange.onSwitchProtocol(_endp);
                                    if (switched!=null)
                                    {
                                        // switched protocol!
                                        exchange = _pipeline;
                                        _pipeline = null;
                                        if (exchange!=null)
                                            _destination.send(exchange);

                                        return switched;
                                    }
                                }

                                if (_pipeline == null)
                                {
                                    if (!isReserved())
                                        _destination.returnConnection(this, close);
                                }
                                else
                                {
                                    if (close)
                                    {
                                        if (!isReserved())
                                            _destination.returnConnection(this,close);

                                        exchange = _pipeline;
                                        _pipeline = null;
                                        _destination.send(exchange);
                                    }
                                    else
                                    {
                                        exchange = _pipeline;
                                        _pipeline = null;
                                        send(exchange);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            _parser.returnBuffers();

            // Do we have more stuff to write?
            if (!_generator.isComplete() && _generator.getBytesBuffered()>0 && _endp.isOpen() && _endp instanceof AsyncEndPoint)
            {
                // Assume we are write blocked!
                ((AsyncEndPoint)_endp).scheduleWrite();
            }
        }

        return this;
    }
    
    public void onInputShutdown() throws IOException
    {
        // TODO
    }
}
