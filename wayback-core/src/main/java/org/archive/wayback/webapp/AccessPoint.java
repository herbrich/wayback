/*
 *  This file is part of the Wayback archival access software
 *   (http://archive-access.sourceforge.net/projects/wayback/).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.wayback.webapp;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.ExceptionRenderer;
import org.archive.wayback.QueryRenderer;
import org.archive.wayback.ReplayDispatcher;
import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.RequestParser;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.accesscontrol.ExclusionFilterFactory;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.SearchResults;
import org.archive.wayback.core.UIResults;
import org.archive.wayback.core.UrlSearchResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.AccessControlException;
import org.archive.wayback.exception.AdministrativeAccessControlException;
import org.archive.wayback.exception.AnchorWindowTooSmallException;
import org.archive.wayback.exception.AuthenticationControlException;
import org.archive.wayback.exception.BadQueryException;
import org.archive.wayback.exception.BaseExceptionRenderer;
import org.archive.wayback.exception.BetterRequestException;
import org.archive.wayback.exception.ConfigurationException;
import org.archive.wayback.exception.ResourceIndexNotAvailableException;
import org.archive.wayback.exception.ResourceNotAvailableException;
import org.archive.wayback.exception.ResourceNotInArchiveException;
import org.archive.wayback.exception.SpecificCaptureReplayException;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;
import org.archive.wayback.resourceindex.filters.WARCRevisitAnnotationFilter;
import org.archive.wayback.resourcestore.resourcefile.WarcResource;
import org.archive.wayback.util.operator.BooleanOperator;
import org.archive.wayback.util.webapp.AbstractRequestHandler;
import org.archive.wayback.util.webapp.ShutdownListener;

/**
 * Retains all information about a particular Wayback configuration
 * within a ServletContext, including holding references to the
 * implementation instances of the primary Wayback classes:
 * 
 * 		RequestParser
 *		ResourceIndex(via WaybackCollection)
 *		ResourceStore(via WaybackCollection)
 *		QueryRenderer
 *		ReplayDispatcher
 *		ExceptionRenderer
 *		ResultURIConverter
 *
 *
 * @author brad
 * @version $Date$, $Revision$
 */
public class AccessPoint extends AbstractRequestHandler 
implements ShutdownListener {
	/** webapp relative location of Interstitial.jsp */
	public final static String INTERSTITIAL_JSP = "jsp/Interstitial.jsp";
	/** argument for Interstitial.jsp target URL */
	public final static String INTERSTITIAL_TARGET = "target";
	/** argument for Interstitial.jsp seconds to delay */
	public final static String INTERSTITIAL_SECONDS = "seconds";

	/** argument for Interstitial.jsp msse for replay date */
	public final static String INTERSTITIAL_DATE = "date";
	/** argument for Interstitial.jsp URL being loaded */
	public final static String INTERSTITIAL_URL = "url";
	
	public final static String REVISIT_STR = "warc/revisit";

	private static final Logger LOGGER = Logger.getLogger(
			AccessPoint.class.getName());

	private boolean exactHostMatch = false;
	private boolean exactSchemeMatch = true;
	private boolean useAnchorWindow = false;
	private boolean useServerName = false;
	private boolean serveStatic = true;
	private boolean bounceToReplayPrefix = false;
	private boolean bounceToQueryPrefix = false;
	private boolean forceCleanQueries = false;

	private String liveWebPrefix = null;
	private String staticPrefix = null;
	private String queryPrefix = null;
	private String replayPrefix = null;
	
	private String interstitialJsp = INTERSTITIAL_JSP;

	private String refererAuth = null;

	private Locale locale = null;

	private Properties configs = null;

	private List<String> filePatterns = null;
	private List<String> fileIncludePrefixes = null;
	private List<String> fileExcludePrefixes = null;

	private WaybackCollection  collection   = null;
	private ExceptionRenderer  exception    = new BaseExceptionRenderer();
	private QueryRenderer      query        = null;
	private RequestParser      parser       = null;
	private ReplayDispatcher   replay       = null;
	private ResultURIConverter uriConverter = null;

	private ExclusionFilterFactory exclusionFactory = null;
	private BooleanOperator<WaybackRequest> authentication = null;
	private long embargoMS = 0;
	private CustomResultFilterFactory filterFactory = null;

	public void init() {
		checkAccessPointAware(collection,exception,query,parser,replay,
				uriConverter,exclusionFactory, authentication, filterFactory);
	}
	
	protected boolean dispatchLocal(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) 
	throws ServletException, IOException {
		if(LOGGER.isLoggable(Level.FINE)) {
			LOGGER.fine("Local dispatch /" + translateRequestPath(httpRequest));
		}
		if(!serveStatic) {
			return false;
		}
//		String contextRelativePath = httpRequest.getServletPath();
		String translatedNoQuery = "/" + translateRequestPath(httpRequest);
//		String absPath = getServletContext().getRealPath(contextRelativePath);
		String absPath = getServletContext().getRealPath(translatedNoQuery);
		
		//IK: added null check for absPath, it may be null (ex. on jetty)
		if (absPath != null) {
			File test = new File(absPath);
			if((test != null) && !test.exists()) {
				return false;
			}
		}
				
		String translatedQ = "/" + translateRequestPathQuery(httpRequest);

		WaybackRequest wbRequest = new WaybackRequest();
//			wbRequest.setContextPrefix(getUrlRoot());
		wbRequest.setAccessPoint(this);
		wbRequest.fixup(httpRequest);
		UIResults uiResults = new UIResults(wbRequest,uriConverter);
		try {
			uiResults.forward(httpRequest, httpResponse, translatedQ);
			return true;
		} catch(IOException e) {
			// TODO: figure out if we got IO because of a missing dispatcher
		}

		return false;
	}

	/**
	 * @param httpRequest HttpServletRequest which is being handled 
	 * @param httpResponse HttpServletResponse which is being handled 
	 * @return true if the request was actually handled
	 * @throws ServletException per usual
	 * @throws IOException per usual
	 */
	public boolean handleRequest(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) 
	throws ServletException, IOException {

		WaybackRequest wbRequest = null;
		boolean handled = false;

		try {
			String inputPath = translateRequestPathQuery(httpRequest);
			Thread.currentThread().setName("Thread " + 
					Thread.currentThread().getId() + " " + getBeanName() + 
					" handling: " + inputPath);
			LOGGER.fine("Handling translated: " + inputPath);
			wbRequest = getParser().parse(httpRequest, this);

			if(wbRequest != null) {
				handled = true;

				// TODO: refactor this code into RequestParser implementations
				wbRequest.setAccessPoint(this);
//				wbRequest.setContextPrefix(getAbsoluteLocalPrefix(httpRequest));
//				wbRequest.setContextPrefix(getUrlRoot());
				wbRequest.fixup(httpRequest);
				// end of refactor

				if(getAuthentication() != null) {
					if(!getAuthentication().isTrue(wbRequest)) {
						throw new AuthenticationControlException(
								"Unauthorized");
					}
				}

				if(getExclusionFactory() != null) {
					ExclusionFilter exclusionFilter = 
						getExclusionFactory().get();
					if(exclusionFilter == null) {
						throw new AdministrativeAccessControlException(
								"AccessControl list unavailable");
					}
					wbRequest.setExclusionFilter(exclusionFilter);
				}
				// TODO: refactor this into RequestParser implementations, so a
				// user could alter requests to change the behavior within a
				// single AccessPoint. For now, this is a simple way to expose
				// the feature to configuration.g
				wbRequest.setExactScheme(isExactSchemeMatch());

				if(wbRequest.isReplayRequest()) {
					if(bounceToReplayPrefix) {
						// we don't accept replay requests on this AccessPoint
						// bounce the user to the right place:
						String suffix = translateRequestPathQuery(httpRequest);
						String replayUrl = replayPrefix + suffix;
						httpResponse.sendRedirect(replayUrl);
						return true;
					}
					handleReplay(wbRequest,httpRequest,httpResponse);
					
				} else {

					if(bounceToQueryPrefix) {
						// we don't accept replay requests on this AccessPoint
						// bounce the user to the right place:
						String suffix = translateRequestPathQuery(httpRequest);
						String replayUrl = queryPrefix + suffix;
						httpResponse.sendRedirect(replayUrl);
						return true;
					}
					wbRequest.setExactHost(isExactHostMatch());
					handleQuery(wbRequest,httpRequest,httpResponse);
				}
			} else {
				handled = dispatchLocal(httpRequest,httpResponse);
			}

		} catch(BetterRequestException e) {
			e.generateResponse(httpResponse);
			handled = true;

		} catch(WaybackException e) {
			if((e instanceof ResourceNotInArchiveException) 
				&& wbRequest.isReplayRequest() 
				&& (getLiveWebPrefix() != null) 
				&& (getLiveWebPrefix().length() > 0)) {

				String liveUrl = 
					getLiveWebPrefix() + wbRequest.getRequestUrl();
						httpResponse.sendRedirect(liveUrl);
			} else {
				logNotInArchive(e,wbRequest);
				getException().renderException(httpRequest, httpResponse, 
						wbRequest, e, getUriConverter());
			}
		}
		
		return handled;
	}
	
	private void logNotInArchive(WaybackException e, WaybackRequest r) {
		// TODO: move this into ResourceNotInArchiveException constructor
		if(e instanceof ResourceNotInArchiveException) {
			String url = r.getRequestUrl();
			StringBuilder sb = new StringBuilder(100);
			sb.append("NotInArchive\t");
			sb.append(getBeanName()).append("\t");
			sb.append(url);
			
			LOGGER.info(sb.toString());
		}
	}

	protected void checkAccessPointAware(Object...os) {
		if(os != null) {
			for(Object o : os) {
				if(o instanceof AccessPointAware) {
					AccessPointAware apa = (AccessPointAware) o;
					apa.setAccessPoint(this);
				}
			}
		}
	}
	
	private void checkInterstitialRedirect(HttpServletRequest httpRequest,
			WaybackRequest wbRequest) 
	throws BetterRequestException {
		if((refererAuth != null) && (refererAuth.length() > 0)) {
			String referer = httpRequest.getHeader("Referer");
			if((referer != null) && (referer.length() > 0) && (!referer.contains(refererAuth))) {
				StringBuffer sb = httpRequest.getRequestURL();
				if(httpRequest.getQueryString() != null) {
					sb.append("?").append(httpRequest.getQueryString());
				}
				StringBuilder u = new StringBuilder();
				u.append(getQueryPrefix());
				u.append(interstitialJsp);
				u.append("?");
				u.append(INTERSTITIAL_SECONDS).append("=").append(5);
				u.append("&");
				u.append(INTERSTITIAL_DATE).append("=").append(wbRequest.getReplayDate().getTime());
				u.append("&");
				u.append(INTERSTITIAL_URL).append("=");
				try {
					u.append(URLEncoder.encode(wbRequest.getRequestUrl(), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					// not gonna happen...
					u.append(wbRequest.getRequestUrl());
				}
				u.append("&");
				u.append(INTERSTITIAL_TARGET).append("=");
				try {
					u.append(URLEncoder.encode(sb.toString(), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					// not gonna happen...
					u.append(sb.toString());
				}

				throw new BetterRequestException(u.toString());
			}
		}
	}
	
	//TODO: Use the canonicalizer instead?
	//Quick check to see if two locations are equal
	protected boolean redirectEquals(String archived, String request)
	{		
		if (request.endsWith("/") && !archived.endsWith("/")) {
			archived += "/";
		}
		
		return archived.equals(request);
	}
	
	protected void handleReplay(WaybackRequest wbRequest, 
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) 
	throws IOException, ServletException, WaybackException {
		Resource httpHeadersResource = null;
		Resource payloadResource = null;
		try {
			
			checkInterstitialRedirect(httpRequest,wbRequest);
			
			PerformanceLogger p = new PerformanceLogger("replay");
			SearchResults results = 
				getCollection().getResourceIndex().query(wbRequest);
			p.queried();
			
			if(!(results instanceof CaptureSearchResults)) {
				throw new ResourceNotAvailableException("Bad results...");
			}
			CaptureSearchResults captureResults = 
				(CaptureSearchResults) results;
			
			CaptureSearchResult closest = null;		
			
			// TODO: check which versions are actually accessible right now?
			closest = 
				getReplay().getClosest(wbRequest, captureResults);
			
			while (true) {
				closest.setClosest(true);
				checkAnchorWindow(wbRequest,closest);
				
				// Support for redirect from the CDX redirectUrl field
				// This was the intended use of the redirect field, but has not actually be tested
				// To enable this functionality, uncomment the lines below
				// This is an optimization that allows for redirects to be handled without loading the original content
				//
				//String redir = closest.getRedirectUrl();
				//if ((redir != null) && !redir.equals("-")) {
				//  String fullRedirect = getUriConverter().makeReplayURI(closest.getCaptureTimestamp(), redir);
				//  throw new BetterRequestException(fullRedirect, Integer.valueOf(closest.getHttpCode()));
				//}
				
				closeResources(payloadResource, httpHeadersResource);
				payloadResource = null;
				httpHeadersResource = null;
				
				try {
					httpHeadersResource = 
						getCollection().getResourceStore().retrieveResource(closest);
	
					if (REVISIT_STR.equals(closest.getMimeType())
							&& closest.isDuplicateDigest()) {
						payloadResource = retrievePayloadForIdenticalContentRevisit(
								httpHeadersResource, captureResults, closest);
					} else {
						payloadResource = httpHeadersResource;
					}
					
					// Ensure that we are not self-redirecting!
					// If the status is a redirect, check that the location or url date's are different from the current request
					// Otherwise, replay the previous matched capture.
					// This chain is unlikely to go past one previous capture, but is possible 
					int status = payloadResource.getStatusCode();
					
					if ((status >= 300) && (status < 400)) {
						String location = payloadResource.getHttpHeaders().get("Location");
						if ((location != null) && (closest.getCaptureTimestamp().equals(wbRequest.getReplayTimestamp())) && redirectEquals(location, wbRequest.getRequestUrl())) {
							closest = closest.getPrevResult();
							if (closest == null) {
								// This should never happen logically, but just in case.
								throw new ResourceNotInArchiveException(
										"After following redirects, the URL " + wbRequest.getRefererUrl() + 
										"captured on or before " + wbRequest.getReplayDate() + "could not be found in the archive");
							}
							continue;
						}
					}
					
					break;
					
				} catch (SpecificCaptureReplayException scre) {
					if (closest.getPrevResult() != null) {
						closest = closest.getPrevResult();
					} else {
						scre.setCaptureContext(captureResults, closest);
						throw scre;
					}
				}
			}
			
			p.retrieved();
			
			// Check for AJAX
			String x_req_with = httpRequest.getHeader("X-Requested-With");
			if ((x_req_with != null)) {
				if (x_req_with.equals("XMLHttpRequest")) {
					wbRequest.setIdentityContext(true);
				}
			}
			
			ReplayRenderer renderer = 
				getReplay().getRenderer(wbRequest, closest, httpHeadersResource, payloadResource);
			
			try {
				renderer.renderResource(httpRequest, httpResponse, wbRequest,
						closest, httpHeadersResource, payloadResource, getUriConverter(), captureResults);
			} catch (SpecificCaptureReplayException scre) {
				scre.setCaptureContext(captureResults, closest);
				throw scre;
			}
			
			p.rendered();
			p.write(wbRequest.getReplayTimestamp() + " " +
					wbRequest.getRequestUrl());
		} finally {
			closeResources(payloadResource, httpHeadersResource);
		}
	}

	protected boolean isWarcRevisitNotModified(Resource warcRevisitResource) {
		if (!(warcRevisitResource instanceof WarcResource)) {
			return false;
		}
		WarcResource wr = (WarcResource) warcRevisitResource;
		Map<String,Object> warcHeaders = wr.getWarcHeaders().getHeaderFields();
		String warcProfile = (String) warcHeaders.get("WARC-Profile");
		return warcProfile != null
				&& warcProfile.equals("http://netpreserve.org/warc/1.0/revisit/server-not-modified");
	}

	/**
	 * If closest 
	 * @param revisitRecord
	 * @param captureResults
	 * @param closest
	 * @return the payload resource
	 * @throws ResourceNotAvailableException
	 * @throws ConfigurationException
	 * @throws AccessControlException 
	 * @throws BadQueryException 
	 * @throws ResourceNotInArchiveException 
	 * @throws ResourceIndexNotAvailableException 
	 * @throws BetterRequestException 
	 * @see WARCRevisitAnnotationFilter
	 */
	protected Resource retrievePayloadForIdenticalContentRevisit(Resource revisitRecord,
			CaptureSearchResults captureResults, CaptureSearchResult closest)
					throws ResourceNotAvailableException, ConfigurationException, ResourceIndexNotAvailableException, ResourceNotInArchiveException, BadQueryException, AccessControlException, BetterRequestException {
		if (!closest.isDuplicateDigest()) {
			LOGGER.warning("record is not a revisit by identical content digest " + closest.getCaptureTimestamp() + " " + closest.getOriginalUrl());
			return null;
		}
		
		CaptureSearchResult payloadLocation = null;
		
		// maybe WARCRevisitAnnotationFilter already found the payload 
		if (closest.getDuplicatePayloadFile() != null && closest.getDuplicatePayloadOffset() != null) {
			payloadLocation = new CaptureSearchResult();
			payloadLocation.setFile(closest.getDuplicatePayloadFile());
			payloadLocation.setOffset(closest.getDuplicatePayloadOffset());
		}

		Map<String, Object> warcHeaders = null;
		
		if (payloadLocation == null) {
			// expect the warc revisit record points us to the payload
			WarcResource wr = (WarcResource) revisitRecord;
			warcHeaders = wr.getWarcHeaders().getHeaderFields();
			String payloadWarcFile = (String) warcHeaders.get("WARC-Refers-To-Filename");
			String offsetStr = (String) warcHeaders.get("WARC-Refers-To-File-Offset");
			if (payloadWarcFile != null && offsetStr != null) {
				payloadLocation = new CaptureSearchResult();
				payloadLocation.setFile(payloadWarcFile);
				payloadLocation.setOffset(Long.parseLong(offsetStr));
			}
		}

		if (payloadLocation != null) {
			try {
				return getCollection().getResourceStore().retrieveResource(payloadLocation);
			} catch (ResourceNotAvailableException e) {
				// one last effort to follow
				payloadLocation = null;
			}
		}

		/*
		 * One last thing to try. This could happen if the revisit record is
		 * url-agnostic and points to a record that was reorganized into a
		 * different warc.
		 */
		// XXX needs testing
		
		String payloadUri = null; 
		String payloadTimestamp = null;
		
		if (warcHeaders == null) {
			WarcResource wr = (WarcResource) revisitRecord;
			warcHeaders = wr.getWarcHeaders().getHeaderFields();
		}
		
		if (warcHeaders != null) {
			payloadUri = (String) warcHeaders.get("WARC-Refers-To-Target-URI");
			payloadTimestamp = (String) warcHeaders.get("WARC-Refers-To-Date");	
		}
		
		if (payloadUri != null && payloadTimestamp != null) {
			WaybackRequest wbr = new WaybackRequest();
			wbr.setReplayTimestamp(payloadTimestamp);
			wbr.setRequestUrl(payloadUri);

			SearchResults results = getCollection().getResourceIndex().query(wbr);
			if(!(results instanceof CaptureSearchResults)) {
				throw new ResourceNotAvailableException("Bad results looking up " + payloadTimestamp + " " + payloadUri);
			}
			CaptureSearchResults payloadCaptureResults = (CaptureSearchResults) results;
			payloadLocation = getReplay().getClosest(wbr, payloadCaptureResults);
		}

		if (payloadLocation == null) {
			throw new ResourceNotAvailableException(
					"unable to find payload for revisit record "
							+ closest.toCanonicalStringMap());
		}

		return getCollection().getResourceStore().retrieveResource(payloadLocation);
	}

	private void checkAnchorWindow(WaybackRequest wbRequest, 
			CaptureSearchResult result) throws AnchorWindowTooSmallException {
		if(isUseAnchorWindow()) {
			String anchorDate = wbRequest.getAnchorTimestamp();
			if(anchorDate != null) {
				long wantTime = wbRequest.getReplayDate().getTime();
				long maxWindow = wbRequest.getAnchorWindow() * 1000;
				if(maxWindow > 0) {
					long closestDistance = Math.abs(wantTime - 
							result.getCaptureDate().getTime());

					if(closestDistance > maxWindow) {
						throw new AnchorWindowTooSmallException("Closest is " + 
								closestDistance + " seconds away, Window is " + 
								maxWindow);
					}
				}
			}
			
		}
	}
	
	protected void handleQuery(WaybackRequest wbRequest, 
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) 
	throws ServletException, IOException, WaybackException {

		PerformanceLogger p = new PerformanceLogger("query");
		SearchResults results = 
			getCollection().getResourceIndex().query(wbRequest);
		p.queried();
		if(results instanceof CaptureSearchResults) {
			CaptureSearchResults cResults = (CaptureSearchResults) results;
			
			// The Firefox proxy plugin maks an XML request to populate the
			// list of available captures, and needs the closest result to
			// the one being replayed to be flagged as such:
			CaptureSearchResult closest = cResults.getClosest();
			if(closest != null) {
				closest.setClosest(true);
			}

			getQuery().renderCaptureResults(httpRequest,httpResponse,wbRequest,
					cResults,getUriConverter());

		} else if(results instanceof UrlSearchResults) {
			UrlSearchResults uResults = (UrlSearchResults) results;
			getQuery().renderUrlResults(httpRequest,httpResponse,wbRequest,
					uResults,getUriConverter());
		} else {
			throw new WaybackException("Unknown index format");
		}
		p.rendered();
		p.write(wbRequest.getRequestUrl());
	}
	
	
	/**
	 * Release any resources associated with this AccessPoint, including
	 * stopping any background processing threads
	 */
	public void shutdown() {
		if(collection != null) {
			try {
				collection.shutdown();
			} catch (IOException e) {
				LOGGER.severe("FAILED collection shutdown"+e.getMessage());
			}
		}
		if(exclusionFactory != null) {
			exclusionFactory.shutdown();
		}
	}
	
	protected void closeResources(Resource payloadResource, Resource httpHeadersResource) throws IOException
	{
		if((payloadResource != null) && payloadResource != httpHeadersResource) {
			payloadResource.close();
		}
		if(httpHeadersResource != null) {
			httpHeadersResource.close();
		}
	}
	
	private String getBestPrefix(String best, String next, String last) {
		if(best != null) {
			return best;
		}
		if(next != null) {
			return next;
		}
		return last;
	}
	
	/*
	 * *******************************************************************
	 * *******************************************************************
	 * 
	 *    ALL GETTER/SETTER BELOW HERE 
	 * 
	 * *******************************************************************
	 * *******************************************************************
	 */
	
	/**
	 * @return the exactHostMatch
	 */
	public boolean isExactHostMatch() {
		return exactHostMatch;
	}

	/**
	 * @param exactHostMatch if true, then only SearchResults exactly matching
	 * 		the requested hostname will be returned from this AccessPoint. If
	 * 		false, then hosts which canonicalize to the same host as requested
	 * 		hostname will be returned (www.)
	 */
	public void setExactHostMatch(boolean exactHostMatch) {
		this.exactHostMatch = exactHostMatch;
	}

	/**
	 * @return the exactSchemeMatch
	 */
	public boolean isExactSchemeMatch() {
		return exactSchemeMatch;
	}

	/**
	 * @param exactSchemeMatch the exactSchemeMatch to set
	 */
	public void setExactSchemeMatch(boolean exactSchemeMatch) {
		this.exactSchemeMatch = exactSchemeMatch;
	}

	/**
	 * @return true if this AccessPoint is configured to useAnchorWindow, that
	 * is, to replay documents only if they are within a certain proximity to
	 * the users requested AnchorDate
	 */
	public boolean isUseAnchorWindow() {
		return useAnchorWindow;
	}

	/**
	 * @param useAnchorWindow , when set to true, causes this AccessPoint to
	 * only replay documents if they are within a certain proximity to
	 * the users requested AnchorDate
	 */
	public void setUseAnchorWindow(boolean useAnchorWindow) {
		this.useAnchorWindow = useAnchorWindow;
	}

	/**
	 * @return the useServerName
	 * @deprecated no longer used, use {replay,query,static}Prefix
	 */
	public boolean isUseServerName() {
		return useServerName;
	}

	/**
	 * @param useServerName the useServerName to set
	 * @deprecated no longer used, use {replay,query,static}Prefix
	 */
	public void setUseServerName(boolean useServerName) {
		this.useServerName = useServerName;
	}

	/**
	 * @return true if this AccessPoint serves static content
	 */
	public boolean isServeStatic() {
		return serveStatic;
	}

	/**
	 * @param serveStatic if set to true, this AccessPoint will serve static 
	 * content, and .jsp files
	 */
	public void setServeStatic(boolean serveStatic) {
		this.serveStatic = serveStatic;
	}

	/**
	 * @return the liveWebPrefix String to use, or null, if this AccessPoint 
	 * does not use the Live Web to fill in documents missing from the archive
	 */
	public String getLiveWebPrefix() {
		return liveWebPrefix;
	}

	/**
	 * @param liveWebPrefix the String URL prefix to use to attempt to retrieve
	 * documents missing from the collection from the live web, on demand.
	 */
	public void setLiveWebPrefix(String liveWebPrefix) {
		this.liveWebPrefix = liveWebPrefix;
	}

	/**
	 * @return the String url prefix to use when generating self referencing 
	 * 			static URLs
	 */
	public String getStaticPrefix() {
		return getBestPrefix(staticPrefix,queryPrefix,replayPrefix);
	}

	/**
	 * @param staticPrefix explicit URL prefix to use when creating self referencing
	 * 		static URLs
	 */
	public void setStaticPrefix(String staticPrefix) {
		this.staticPrefix = staticPrefix;
	}

	/**
	 * @return the String url prefix to use when generating self referencing 
	 * 			replay URLs
	 */
	public String getReplayPrefix() {
		return getBestPrefix(replayPrefix,queryPrefix,staticPrefix);
	}

	/**
	 * @param replayPrefix explicit URL prefix to use when creating self referencing
	 * 		replay URLs
	 */
	public void setReplayPrefix(String replayPrefix) {
		this.replayPrefix = replayPrefix;
	}

	/**
	 * @param queryPrefix explicit URL prefix to use when creating self referencing
	 * 		query URLs
	 */
	public void setQueryPrefix(String queryPrefix) {
		this.queryPrefix = queryPrefix;
	}

	/**
	 * @return the String url prefix to use when generating self referencing 
	 * 			replay URLs
	 */
	public String getQueryPrefix() {
		return getBestPrefix(queryPrefix,staticPrefix,replayPrefix);
	}

	/**
	 * @param interstitialJsp the interstitialJsp to set
	 */
	public void setInterstitialJsp(String interstitialJsp) {
		this.interstitialJsp = interstitialJsp;
	}

	/**
	 * @return the interstitialJsp
	 */
	public String getInterstitialJsp() {
		return interstitialJsp;
	}

	/**
	 * @param urlRoot explicit URL prefix to use when creating ANY self 
	 * referencing URLs
	 * @deprecated use setQueryPrefix, setReplayPrefix, setStaticPrefix
	 */
	public void setUrlRoot(String urlRoot) {
		this.queryPrefix = urlRoot;
		this.replayPrefix = urlRoot;
		this.staticPrefix = urlRoot;
	}

	/**
	 * @return the String url prefix used when generating self referencing 
	 * 			URLs
	 * @deprecated use getQueryPrefix, getReplayPrefix, getStaticPrefix
	 */
	public String getUrlRoot() {
		return getBestPrefix(queryPrefix,staticPrefix,replayPrefix);
	}

	/**
	 * @return explicit Locale to use within this AccessPoint.
	 */
	public Locale getLocale() {
		return locale;
	}

	/**
	 * @param locale explicit Locale to use for requests within this 
	 * 		AccessPoint. If not set, will attempt to use the one specified by
	 * 		each requests User Agent via HTTP headers
	 */
	public void setLocale(Locale locale) {
		this.locale = locale;
	}

	/**
	 * @return the generic customization Properties used with this AccessPoint,
	 * generally to tune the UI
	 */
	public Properties getConfigs() {
		return configs;
	}

	/**
	 * @param configs the generic customization Properties to use with this
	 * AccessPoint, generally used to tune the UI
	 */
	public void setConfigs(Properties configs) {
		this.configs = configs;
	}

	/**
	 * @return List of file patterns that will be matched when querying the 
	 * ResourceIndex
	 */
	public List<String> getFilePatterns() {
		return filePatterns;
	}

	/**
	 * @param filePatterns List of file Patterns (regular expressions) that
	 * 		will be matched when querying the ResourceIndex - only SearchResults
	 *      matching one of these patterns will be returned.
	 */
	public void setFilePatterns(List<String> filePatterns) {
		this.filePatterns = filePatterns;
	}

	/**
	 * @return List of file String prefixes that will be matched when querying 
	 * 		the ResourceIndex
	 */
	public List<String> getFileIncludePrefixes() {
		return fileIncludePrefixes;
	}

	/**
	 * @param fileIncludePrefixes List of String file prefixes that will be matched
	 * 		when querying the ResourceIndex - only SearchResults from files 
	 * 		with a prefix matching one of those in this List will be returned.
	 */
	public void setFileIncludePrefixes(List<String> fileIncludePrefixes) {
		this.fileIncludePrefixes = fileIncludePrefixes;
	}

	/**
	 * @return List of file String prefixes that will be matched when querying 
	 * 		the ResourceIndex
	 */
	public List<String> getFileExcludePrefixes() {
		return fileExcludePrefixes;
	}

	/**
	 * @param fileExcludePrefixes List of String file prefixes that will be matched
	 * 		when querying the ResourceIndex - only SearchResults from files 
	 * 		with a prefix matching one of those in this List will be returned.
	 */
	public void setFileExcludePrefixes(List<String> fileExcludePrefixes) {
		this.fileExcludePrefixes = fileExcludePrefixes;
	}


	
	/**
	 * @return the WaybackCollection used by this AccessPoint
	 */
	public WaybackCollection getCollection() {
		return collection;
	}

	/**
	 * @param collection the WaybackCollection to use with this AccessPoint
	 */
	public void setCollection(WaybackCollection collection) {
		this.collection = collection;
	}

	/**
	 * @return the ExceptionRenderer in use with this AccessPoint
	 */
	public ExceptionRenderer getException() {
		return exception;
	}

	/**
	 * @param exception the ExceptionRender to use with this AccessPoint
	 */
	public void setException(ExceptionRenderer exception) {
		this.exception = exception;
	}

	/**
	 * @return the QueryRenderer to use with this AccessPoint
	 */
	public QueryRenderer getQuery() {
		return query;
	}
	
	/**
	 * @param query the QueryRenderer responsible for returning query data to
	 * clients.
	 */
	public void setQuery(QueryRenderer query) {
		this.query = query;
	}

	/**
	 * @return the RequestParser used by this AccessPoint to attempt to 
	 * translate incoming HttpServletRequest objects into WaybackRequest 
	 * objects
	 */
	public RequestParser getParser() {
		return parser;
	}
	
	/**
	 * @param parser the RequestParser to use with this AccessPoint
	 */
	public void setParser(RequestParser parser) {
		this.parser = parser;
	}

	/**
	 * @return the ReplayDispatcher to use with this AccessPoint, responsible
	 * for returning an appropriate ReplayRenderer given the user request and
	 * the returned document type.
	 */
	public ReplayDispatcher getReplay() {
		return replay;
	}

	/**
	 * @param replay the ReplayDispatcher to use with this AccessPoint.
	 */
	public void setReplay(ReplayDispatcher replay) {
		this.replay = replay;
	}

	/**
	 * @return the ResultURIConverter used to construct Replay URLs within this
	 * AccessPoint
	 */
	public ResultURIConverter getUriConverter() {
		return uriConverter;
	}

	/**
	 * @param uriConverter the ResultURIConverter to use with this AccessPoint
	 * to construct Replay URLs
	 */
	public void setUriConverter(ResultURIConverter uriConverter) {
		this.uriConverter = uriConverter;
	}


	/**
	 * @return the ExclusionFilterFactory in use with this AccessPoint
	 */
	public ExclusionFilterFactory getExclusionFactory() {
		return exclusionFactory;
	}

	/**
	 * @param exclusionFactory all requests to this AccessPoint will create an
	 * 		exclusionFilter from this factory when handling requests
	 */
	public void setExclusionFactory(ExclusionFilterFactory exclusionFactory) {
		this.exclusionFactory = exclusionFactory;
	}

	/**
	 * @return the configured AuthenticationControl BooleanOperator in use with 
	 *      this AccessPoint.
	 */
	public BooleanOperator<WaybackRequest> getAuthentication() {
		return authentication;
	}

	/**
	 * @param auth the BooleanOperator which determines if incoming
	 * 		requests are allowed to connect to this AccessPoint.
	 */
	public void setAuthentication(BooleanOperator<WaybackRequest> auth) {
		this.authentication = auth;
	}

	/**
	 * @return the refererAuth
	 */
	public String getRefererAuth() {
		return refererAuth;
	}

	/**
	 * @param refererAuth the refererAuth to set
	 */
	public void setRefererAuth(String refererAuth) {
		this.refererAuth = refererAuth;
	}

	/**
	 * @return the bounceToReplayPrefix
	 */
	public boolean isBounceToReplayPrefix() {
		return bounceToReplayPrefix;
	}

	/**
	 * @param bounceToReplayPrefix the bounceToReplayPrefix to set
	 */
	public void setBounceToReplayPrefix(boolean bounceToReplayPrefix) {
		this.bounceToReplayPrefix = bounceToReplayPrefix;
	}
	/**
	 * @return the bounceToQueryPrefix
	 */
	public boolean isBounceToQueryPrefix() {
		return bounceToQueryPrefix;
	}

	/**
	 * @param bounceToQueryPrefix the bounceToQueryPrefix to set
	 */
	public void setBounceToQueryPrefix(boolean bounceToQueryPrefix) {
		this.bounceToQueryPrefix = bounceToQueryPrefix;
	}

	/**
	 * @return the configured number of MS for min age to return from the index
	 */
	public long getEmbargoMS() {
		return embargoMS;
	}
	/**
	 * @param ms minimum number of MS age for content to be served from the index
	 */
	public void setEmbargoMS(long ms) {
		this.embargoMS = ms;
	}

	/**
	 * @return the forceCleanQueries
	 */
	public boolean isForceCleanQueries() {
		return forceCleanQueries;
	}

	/**
	 * @param forceCleanQueries the forceCleanQueries to set
	 */
	public void setForceCleanQueries(boolean forceCleanQueries) {
		this.forceCleanQueries = forceCleanQueries;
	}

	/**
	 * @param filterFactory the filterFactory to set
	 */
	public void setFilterFactory(CustomResultFilterFactory filterFactory) {
		this.filterFactory = filterFactory;
	}

	/**
	 * @return the filterFactory
	 */
	public CustomResultFilterFactory getFilterFactory() {
		return filterFactory;
	}
}
