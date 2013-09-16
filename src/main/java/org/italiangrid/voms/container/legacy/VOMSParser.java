package org.italiangrid.voms.container.legacy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VOMSParser extends HttpParser {

	public static final Logger log = LoggerFactory.getLogger(VOMSParser.class);
	
	public enum ParserStatus {
		START, 
		FOUND_ZERO, 
		FOUND_VOMS_LEGACY_VOMS_REQUEST, 
		DONE;
	}
	
	private static final byte ZERO = '0';
	private static final byte LEFT_BRACKET = '<';
	
	private ParserStatus _state = ParserStatus.START;
	private Buffer _vomsBuffer = null;
	private final EndPoint _vomsEndpoint;
	private final EventHandler _vomsHandler;
	
	private final JettyHTTPGetRequestBuffers _requestBuffers;
	private final VOMSXMLRequestTranslator _xmlRequestTranslator;
	
	public static final Charset charset = Charset.forName("UTF-8");
	public static final CharsetDecoder decoder = charset.newDecoder();
	
	private static final Map<Buffer, Buffer> legacyRequestHeaders;
	
	static {
		legacyRequestHeaders = new HashMap<Buffer, Buffer>();
	
		for (LegacyHTTPHeader e: LegacyHTTPHeader.values()){
			Buffer name = new DirectNIOBuffer(e.getHeaderName().length());
			name.put(e.getHeaderName().getBytes());
			Buffer value = new DirectNIOBuffer(e.getHeaderValue().length());
			value.put(e.getHeaderValue().getBytes());
			legacyRequestHeaders.put(name, value);
		}
		
	}
	
	
	public VOMSParser(Buffer buffer, EventHandler handler) {

		super(buffer, handler);
		_vomsEndpoint = null;
		_vomsHandler = handler;
		_requestBuffers = newRequestBuffers();
		_xmlRequestTranslator = newXMLRequestTranslator(_requestBuffers);
		
	}

	public VOMSParser(Buffers buffers, EndPoint endp, EventHandler handler) {

		super(buffers, endp, handler);
		_vomsEndpoint = endp;
		_vomsHandler = handler;
		_requestBuffers = newRequestBuffers();
		_xmlRequestTranslator = newXMLRequestTranslator(_requestBuffers);

	}

	protected JettyHTTPGetRequestBuffers newRequestBuffers(){
		return new HTTPRequestBuffers();
	}
	
	protected VOMSXMLRequestTranslator newXMLRequestTranslator(
		JettyHTTPGetRequestBuffers buffers){
		return new VOMSXMLRequestTranslatorImpl();
	}
	
	
	protected void setState(ParserStatus s){
		_state = s;
	}
	
	
	protected boolean isDone() {
		return _state.equals(ParserStatus.DONE);
	}
	
	protected int fillBuffer() throws IOException {
		
		if (_vomsBuffer == null)
			_vomsBuffer = getHeaderBuffer();

		if (_vomsEndpoint != null) {

			if (_vomsBuffer.space() == 0) {
				_vomsBuffer.clear();
				throw new IllegalStateException("VOMS buffer full!");
			}

			int filled = _vomsEndpoint.fill(_vomsBuffer);
			return filled;
		}

		return -1;
	}
	
	
	protected void notifyHTTPRequestComplete() throws IOException{
		
		_vomsHandler.startRequest(_requestBuffers.getMethodBuffer(),
			_requestBuffers.getURIBuffer(),
			_requestBuffers.getVersionBuffer());
		
		for (Map.Entry<Buffer, Buffer> e: legacyRequestHeaders.entrySet()){
			Buffer headerName = e.getKey();
			Buffer headerValue = e.getValue();
			_vomsHandler.parsedHeader(headerName, headerValue);
		}
		
		_vomsHandler.headerComplete();
	}
	
	
	protected void parseXML(){
		// Save indexes in case something goes wrong
		int getIndex = _vomsBuffer.getIndex();
		int putIndex = _vomsBuffer.putIndex();
		
		boolean success = _xmlRequestTranslator
			.translateLegacyRequest(_vomsBuffer,_requestBuffers);
		
		if (success){
			setState(ParserStatus.DONE);
			try {
				notifyHTTPRequestComplete();
			} catch (IOException e) {
				log.error("Error completing legacy http request translation: {}", 
					e.getMessage(), e);
				_requestBuffers.clearBuffers();
			}
		}else{
			// reset buffer indexes
			_vomsBuffer.setGetIndex(getIndex);
			_vomsBuffer.setPutIndex(putIndex);
		}
	}

	protected int parseVOMSRequest() throws IOException {

		int progress = 0;

		if (isDone())
			return 0;

		IOException ex;
		
		if (_vomsBuffer == null)
			_vomsBuffer = getHeaderBuffer();

		if (_vomsBuffer.length() == 0) {
		
			int filled = -1;
			
			try{
			
				filled = fillBuffer();
			
			}catch(IOException e){
				ex = e;
				log.debug("Error filling buffer: {}", e.getMessage(), e);
			}
				
			if (filled > 0)
				progress++;
			else if (filled < 0){
				log.debug("Error reading from channel, declaring EOF and parser done");
				setState(ParserStatus.DONE);
				_vomsHandler.earlyEOF();
			}
		}
		
		byte ch;
		
		while (!isDone() && (_vomsBuffer.length() > 0)){
			
			if (_state ==  ParserStatus.START || _state == ParserStatus.FOUND_ZERO){
				ch = _vomsBuffer.peek();
				if (ch != ZERO && ch != LEFT_BRACKET){
					// No VOMS nonsense around here, we're done, fall back to 
					// Jetty HTTP parser
					_state = ParserStatus.DONE;
					return progress;
				}
				
				// Skip trailing zero
				if (ch == ZERO){
					_vomsBuffer.get();
					progress++;
					_state = ParserStatus.FOUND_ZERO;
			
				} else {
					
					_state = ParserStatus.FOUND_VOMS_LEGACY_VOMS_REQUEST;
					progress = progress + _vomsBuffer.length();
					parseXML();					
				}
				
				continue;
			}
			
		}
		
		return progress;
	}

	@Override
	public boolean parseAvailable() throws IOException {
		
		if (_state.equals(ParserStatus.DONE))
			return super.parseAvailable();

		boolean progress = parseVOMSRequest() > 0;

		while (!isDone() && _vomsBuffer != null && _vomsBuffer.length() > 0) {
			progress |= (parseVOMSRequest() > 0);
		}

		return progress;

	}

	@Override
	public boolean isComplete() {

		if (_state.equals(ParserStatus.DONE))
			return super.isComplete();
		else
			return false;
	}

	@Override
	public void reset() {
		super.reset();
		_state = ParserStatus.START;
		_vomsBuffer = null;
		_requestBuffers.clearBuffers();
	}
}
