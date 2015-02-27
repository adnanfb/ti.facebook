/**
 * This file was auto-generated by the Titanium Module SDK helper for Android
 * Copyright (c) 2014 by Mark Mokryn All Rights Reserved.
 * Licensed under the terms of the Apache Public License 2.0
 * Please see the LICENSE included with this distribution for details.
 *
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2015 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 *
 */
package facebook;

import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.annotations.Kroll;
import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.TiApplication;
import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.facebook.AppEventsLogger;
import com.facebook.FacebookAuthorizationException;
import com.facebook.FacebookException;
import com.facebook.FacebookOperationCanceledException;
import com.facebook.FacebookRequestError;
import com.facebook.HttpMethod;
import com.facebook.Request;
import com.facebook.RequestAsyncTask;
import com.facebook.Response;
import com.facebook.Session;
import com.facebook.SessionDefaultAudience;
import com.facebook.SessionState;
import com.facebook.Settings;
import com.facebook.Session.NewPermissionsRequest;
import com.facebook.UiLifecycleHelper;
import com.facebook.model.GraphObject;
import com.facebook.model.GraphObjectList;
import com.facebook.model.GraphPlace;
import com.facebook.model.GraphUser;
import com.facebook.model.OpenGraphAction;
import com.facebook.model.OpenGraphObject;
import com.facebook.widget.FacebookDialog;
import com.facebook.widget.WebDialog;
import com.facebook.widget.WebDialog.OnCompleteListener;

@Kroll.module(name="Facebook", id="facebook")
public class TiFacebookModule extends KrollModule
{

	// Standard Debugging variables
	private static final String TAG = "TiFacebookModule";
	public static final String EVENT_LOGIN = "login";
	public static final String EVENT_LOGOUT = "logout";
	public static final String EVENT_TOKEN_UPDATED = "tokenUpdated";
	public static final String PROPERTY_SUCCESS = "success";
	public static final String PROPERTY_CANCELLED = "cancelled";
	public static final String PROPERTY_ERROR = "error";
	public static final String PROPERTY_CODE = "code";
	public static final String PROPERTY_DATA = "data";
	public static final String PROPERTY_UID = "uid";
	public static final String PROPERTY_RESULT = "result";
	public static final String PROPERTY_PATH = "path";
	public static final String PROPERTY_METHOD = "method";
	public static final String EVENT_SHARE_COMPLETE = "shareCompleted";
	public static final String EVENT_REQUEST_DIALOG_COMPLETE = "requestDialogCompleted";
	
    @Kroll.constant public static final int AUDIENCE_NONE = 0;
    @Kroll.constant public static final int AUDIENCE_ONLY_ME = 1;
    @Kroll.constant public static final int AUDIENCE_FRIENDS = 2;
    @Kroll.constant public static final int AUDIENCE_EVERYONE = 3;
    @Kroll.constant public static final int SSO_WITH_FALLBACK = 0;
    @Kroll.constant public static final int SSO_ONLY = 1;
    @Kroll.constant public static final int SUPPRESS_SSO = 2;

	private static TiFacebookModule module;
	private static String uid = null;
	private static String[] permissions = new String[]{};

	private KrollFunction permissionCallback = null;
	private boolean ignoreClose = false;
	private boolean loggedIn = false;
	private int meRequestTimeout;
	private UiLifecycleHelper uiLifecycleHelper;
	
	public TiFacebookModule()
	{
		super();
		module = this;
	}

	@Kroll.onAppCreate
	public static void onAppCreate(TiApplication app)
	{
		Log.d(TAG, "Facebook module using SDK version " + Settings.getSdkVersion());
		// put module init code that needs to run when the application is created
	}

	private Session.StatusCallback callback = new Session.StatusCallback() {
		@Override
	    public void call(Session session, SessionState state, Exception exception) {
			Log.d(TAG, "onSessionStateChange called");
			onSessionStateChange(session, state, exception);
		}
	};

	public void onSessionStateChange(Session session, SessionState state, Exception exception) {
		// We consider Opened, Closed, Cancelled and Error "final" states and finish off
		// All other states are "intermediate"

		KrollDict data = new KrollDict();

		if (exception instanceof FacebookOperationCanceledException) {
			ignoreClose = false;
			loggedIn = false;
			Log.d(TAG, "StatusCallback cancelled");
			data.put("cancelled", true);
			data.put("success", false);
			fireEvent(EVENT_LOGIN, data);
			if (permissionCallback != null) {
				permissionCallback.callAsync(getKrollObject(), data);
				permissionCallback = null;
			}
		} else if (exception instanceof FacebookAuthorizationException) {
			// login error
			ignoreClose = false;
			loggedIn = false;
			Log.e(TAG, "StatusCallback error: " + exception.getMessage());
			data.put("error", exception.getMessage());
			data.put("success", false);
			data.put("cancelled", false);
			fireEvent(EVENT_LOGIN, data);
			if (permissionCallback != null) {
				permissionCallback.callAsync(getKrollObject(), data);
				permissionCallback = null;
			}
		} else if (exception != null) {
			// some other error
			loggedIn = false;
			Log.e(TAG, "StatusCallback error: " + exception.getMessage() + " state: " + state);
			data.put("error", "Please check your network connection and try again");
			data.put("success", false);
			data.put("cancelled", false);
			if (permissionCallback != null) {
				permissionCallback.callAsync(getKrollObject(), data);
				permissionCallback = null;
			}
			if (ignoreClose) {
				ignoreClose = false;
				if (state == SessionState.CLOSED_LOGIN_FAILED) {
					return; // we sometimes see this immediately after login attempt but login continues
				}
			}
			fireEvent(EVENT_LOGIN, data);
		} else if (state.isOpened()) {
			// fire login
			ignoreClose = false;
			Log.d(TAG, "StatusCallback opened");
			if (state == SessionState.OPENED_TOKEN_UPDATED) {
				Log.d(TAG, "Session.state == OPENED_TOKEN_UPDATED");
				if (permissionCallback != null) {
					data.put("success", true);
					permissionCallback.callAsync(getKrollObject(), data);
					permissionCallback = null;
				}
				fireEvent(EVENT_TOKEN_UPDATED, null);
				return;
			}
			if (loggedIn) {
				// do not fire this again
				// for example, may happen when refresh permissions or a request for new permissions succeeds
				return;
			}
			loggedIn = true;
			data.put("success", true);
			data.put("cancelled", false);
			makeMeRequest(session);
		} else if (state.isClosed()) {
			Log.d(TAG, "StatusCallback closed");
			if (ignoreClose) {
				// since we sometimes see Closed immediately after open is called
				Log.d(TAG, "Ignore close");
				ignoreClose = false;
				return;
			}
			if (!loggedIn) {
				// do not fire if already !loggedIn
				return;
			}
			loggedIn = false;
			Log.d(TAG, "Fire event logout");
			fireEvent(EVENT_LOGOUT, null);
			if (permissionCallback != null) {
				data.put("logout", true);
				permissionCallback.callAsync(getKrollObject(), data);
				permissionCallback = null;
			}
		} else {
			// log state
			ignoreClose = false;
			Log.d(TAG, "StatusCallback other state: " + state);
		}
	}

	public static TiFacebookModule getFacebookModule() {
		return module;
	}

	public Session.StatusCallback getSessionStatusCallback() {
		return callback;
	}

	public boolean getLoggedInVar() {
		return loggedIn;
	}

	public void makeMeRequest(final Session session) {
		// Make an API call to get user data and define a
		// new callback to handle the response.
		new Handler(Looper.getMainLooper()).post(new Runnable() {
		    @Override
		    public void run() {
				Request request = Request.newMeRequest(session, new Request.GraphUserCallback() {
					@Override
					public void onCompleted(GraphUser user, Response response) {
						// If the response is successful
						FacebookRequestError err = response.getError();
						KrollDict data = new KrollDict();
						if (session == Session.getActiveSession() && err == null) {
							if (user != null) {
								Log.d(TAG, "user is not null");
								uid = user.getId();
								data.put(PROPERTY_CANCELLED, false);
								data.put(PROPERTY_SUCCESS, true);
								data.put(PROPERTY_UID, uid);
								JSONObject userJson = user.getInnerJSONObject();
								data.put(PROPERTY_DATA, userJson.toString());
								data.put(PROPERTY_CODE, 0);
								Log.d(TAG, "firing login event from module");
								fireEvent(EVENT_LOGIN, data);
							}
						}
						if (err != null) {
							String errorString = handleError(err);
							Log.e(TAG, "me request callback error");
							Log.e(TAG, "error userActionMessageId: " + err.getUserActionMessageId());
							Log.e(TAG, "should notify user: " + err.shouldNotifyUser());
							Log.e(TAG, "error message: " + err.getErrorMessage());
							Session session = Session.getActiveSession();
							if (session != null && !session.isClosed()) {
								session.closeAndClearTokenInformation();
							};
							data.put(PROPERTY_ERROR, errorString);
							fireEvent(EVENT_LOGIN, data);
						}
					}
				});
				HttpURLConnection connection = Request.toHttpConnection(request);
				connection.setConnectTimeout(meRequestTimeout);
				connection.setReadTimeout(meRequestTimeout);
				RequestAsyncTask task = new RequestAsyncTask(connection, request);
				task.execute();
		    }
		});
	}
	
	private String handleError(FacebookRequestError error) {
		String errorMessage = null;

		if (error == null) {
			errorMessage = "An error occurred. Please try again.";
		} else {
			switch (error.getCategory()) {
			case AUTHENTICATION_RETRY:
				// tell the user what happened by getting the message id, and
				// retry the operation later
				if (error.shouldNotifyUser()) {
					errorMessage = TiApplication.getInstance().getString(error.getUserActionMessageId());
				} else {
					errorMessage = "Please login again";
				}
				break;

			case AUTHENTICATION_REOPEN_SESSION:
				// close the session and reopen it.
				errorMessage = "Please login again";
				break;

			case PERMISSION:
				// request the publish permission
				errorMessage = "The app does not have the required permissions. Please login again to provide additional permissions.";
				break;

			case SERVER:
			case THROTTLING:
				// this is usually temporary, don't clear the fields, and
				// ask the user to try again
				errorMessage = "Please retry, request failed due to heavy load";
				break;

			case BAD_REQUEST:
				// this is likely a coding error, ask the user to file a bug
				errorMessage = "Bad request, contact the developer and log a bug";
				break;

			case OTHER:
			case CLIENT:
			default:
				// an unknown issue occurred, this could be a code error, or
				// a server side issue, log the issue, and either ask the
				// user to retry, or file a bug
				errorMessage = "Please check your network connection and try again.";
			}
		}
		return errorMessage;
	}
	
	// Properties and methods
	
	@Kroll.getProperty @Kroll.method
	public boolean getCanPresentShareDialog()
	{
		return FacebookDialog.canPresentShareDialog(TiApplication.getInstance(), 
				FacebookDialog.ShareDialogFeature.SHARE_DIALOG);
	}	
	
	@Kroll.getProperty @Kroll.method
	public boolean getCanPresentOpenGraphActionDialog()
	{
		return FacebookDialog.canPresentOpenGraphActionDialog(TiApplication.getInstance(), 
				FacebookDialog.OpenGraphActionDialogFeature.OG_ACTION_DIALOG);
	}
	
	@Kroll.method
	public void requestWithGraphPath(String path, KrollDict params, String httpMethod, final KrollFunction callback){
		Session session = Session.getActiveSession();
		if (httpMethod == null || httpMethod.length() == 0) {
			httpMethod = "GET";
		}
		Bundle paramBundle = Utils.mapToBundle(params);
		Request request = new Request(session, path, paramBundle, 
				HttpMethod.valueOf(httpMethod.toUpperCase()),  
				new Request.Callback() {
				@Override
				public void onCompleted(Response response) {
					FacebookRequestError err = response.getError();
					KrollDict data = new KrollDict();
					if (err != null) {
						String errorString = handleError(err);
						// Handle errors, will do so later.
						Log.e(TAG, "requestWithGraphPath callback error");
						Log.e(TAG, "error userActionMessageId: " + err.getUserActionMessageId());
						Log.e(TAG, "should notify user: " + err.shouldNotifyUser());
						Log.e(TAG, "error message: " + err.getErrorMessage());
						data.put(PROPERTY_ERROR, errorString);
						callback.callAsync(getKrollObject(), data);
						return;
					}

					data.put(PROPERTY_SUCCESS, true);
					String responseString = "";
					GraphObject graphObject = response.getGraphObject();
					if (graphObject != null) {
						JSONObject responseJsonObject = graphObject.getInnerJSONObject();
						responseString = responseJsonObject.toString();
					} else {
						GraphObjectList<GraphObject> graphObjectList = response.getGraphObjectList();
						if (graphObjectList != null) {
							JSONArray responseJsonArray = graphObjectList.getInnerJSONArray();
							responseString = responseJsonArray.toString();
						}
					}
					data.put(PROPERTY_RESULT, responseString);
					callback.callAsync(getKrollObject(), data);
				}});
		request.executeAsync();
	}
	
	@Kroll.method
	public void logCustomEvent(String event) {
		Activity activity = TiApplication.getInstance().getCurrentActivity();
		AppEventsLogger logger = AppEventsLogger.newLogger(activity);
		if (logger != null) {
			logger.logEvent(event);
		}
	}
	
	@Kroll.getProperty @Kroll.method
	public String getUid() {
		return uid;
	}

	@Kroll.getProperty
	public String getAccessToken() {
		Log.d(TAG, "get accessToken");
		return Session.getActiveSession().getAccessToken();
	}
	
	@Kroll.getProperty @Kroll.method
	public Date getExpirationDate() {
		return Session.getActiveSession().getExpirationDate();
	}	
	
	@Kroll.getProperty @Kroll.method
	public boolean getLoggedIn() {
		Session session = Session.getActiveSession();
		if (session != null && session.isOpened()) {
			loggedIn = true;
			return true;
		}
		loggedIn = false;
		return false;
	}
	
	@Kroll.getProperty @Kroll.method
	public String[] getPermissions() {
		Session activeSession = Session.getActiveSession();
		if (activeSession != null){
			List<String> permissionsList = activeSession.getPermissions();
			String[] permissionsArray = permissionsList.toArray(new String[permissionsList.size()]);
			return permissionsArray;			
		}
		return null;	
	}
	
	@Kroll.setProperty @Kroll.method
	public void setPermissions(Object[] permissions) {
		TiFacebookModule.permissions = Arrays.copyOf(permissions, permissions.length, String[].class);
	}
	
	@Kroll.method
	public void initialize(@Kroll.argument(optional=true) int timeout) {
		meRequestTimeout = timeout;
		Log.d(TAG, "initialize called with timeout: " + meRequestTimeout);
		Session session = Session.openActiveSessionFromCache(TiApplication.getInstance());
		if (session != null){
			Log.d(TAG, "cached session found");
			loggedIn = true;
			Log.d(TAG, "session opened from cache, state: " + session.getState());
			makeMeRequest(session);
		} else {
			loggedIn = false;
			Log.d(TAG, "no cached session, user will need to login");
		}
	}
	
	@Kroll.method
	public void authorize() {
		Activity activity = TiApplication.getInstance().getCurrentActivity();
		ignoreClose = true;
		Log.d(TAG, "authorize called, permissions length: " + TiFacebookModule.permissions.length);
		for (int i=0; i < TiFacebookModule.permissions.length; i++){
			Log.d(TAG, "authorizing permission: " + TiFacebookModule.permissions[i]);
		}
		Session.openActiveSession(activity, true, Arrays.asList(TiFacebookModule.permissions), callback);
	}
	
	@Kroll.method
	public void refreshPermissionsFromServer() {
		Session.getActiveSession().refreshPermissions();
	}

	@Kroll.method
	public void logout() {
		Log.d(TAG, "logout in facebook proxy");
		Session session = Session.getActiveSession();
		if (session != null && !session.isClosed()) {
			Log.d(TAG, "closing session");
			session.closeAndClearTokenInformation();
		} else {
			Log.d(TAG, "session is null or already closed");
		}
	}
	
	@Kroll.method
	public void presentShareDialog(@Kroll.argument(optional = true) final KrollDict args)
	{
		FacebookDialog shareDialog = null;
		if (args == null || args.isEmpty()) {
			shareDialog = new FacebookDialog.ShareDialogBuilder(TiApplication.getInstance().getCurrentActivity())
				.build();
		} else {
			
			String url = (String) args.get("url");
			if  (url == null) {
				url = (String) args.get("link");
			}
			String name = (String) args.get("name");
			String caption = (String) args.get("caption");
			String picture = (String) args.get("picture");
			
			String namespaceObject = (String) args.get("namespaceObject");
			String namespaceAction = (String) args.get("namespaceAction");
			String objectName = (String) args.get("objectName");
			String imageUrl = (String) args.get("imageUrl");
			String title = (String) args.get("title");
			String description = (String) args.get("description");
			String placeId = (String) args.get("placeId");
			if (url != null && namespaceObject == null) {
				shareDialog = new FacebookDialog.ShareDialogBuilder(TiApplication.getInstance().getCurrentActivity())
		        .setLink(url).setName(name).setCaption(caption).setPicture(picture).setDescription(description)
		        .build();
			} else {
				OpenGraphObject ogObject = OpenGraphObject.Factory.createForPost(namespaceObject);
				ogObject.setProperty("title", title);
				ogObject.setProperty("image", imageUrl);
				ogObject.setProperty("url", url);
				ogObject.setProperty("description", description);

				OpenGraphAction action = OpenGraphAction.Factory.createForPost(namespaceAction);
				action.setProperty(objectName, ogObject);

				if (placeId != null){
					GraphPlace place = GraphObject.Factory.create(GraphPlace.class);
					place.setId(placeId);
					action.setPlace(place);
				}

				shareDialog = new FacebookDialog.OpenGraphActionDialogBuilder(TiApplication.getInstance().getCurrentActivity(), action, objectName)
					.build();
			}
			
		}
		if (shareDialog != null && uiLifecycleHelper != null) {
			uiLifecycleHelper.trackPendingDialogCall(shareDialog.present());
		} else if (shareDialog != null) {
			shareDialog.present();
		}
	}
	
	@Kroll.method
	public void presentWebShareDialog(@Kroll.argument(optional = true) final KrollDict args)
	{
		//Important note: WebDialog uses a WebView. When this is created normally via Titanium SDK, this will not work correctly.
		//This is solved by explicitly running this code on the UiThread.
		//This is mentioned in the Android docs here: https://developer.android.com/guide/webapps/migrating.html
		//If you call methods on WebView from any thread other than your app's UI thread, it can cause unexpected results. 
		//For example, if your app uses multiple threads, you can use the runOnUiThread() method to ensure your code 
		//executes on the UI thread
		TiApplication.getInstance().getCurrentActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				WebDialog feedDialog = null;
				if (args == null || args.isEmpty()) {
					feedDialog = new WebDialog.FeedDialogBuilder(TiApplication.getInstance().getCurrentActivity(),
									Session.getActiveSession())
					.setOnCompleteListener(new OnCompleteListener() {
						@Override
						public void onComplete(Bundle values,
								FacebookException error) {
							KrollDict data = new KrollDict();
							if (error == null) {
								// When the story is posted, echo the success
								// and the post Id.
								final String postId = values.getString("post_id");
								if (postId != null) {
									data.put(PROPERTY_SUCCESS, true);
									data.put(PROPERTY_CANCELLED, false);
									data.put(PROPERTY_RESULT, postId);
									fireEvent(EVENT_SHARE_COMPLETE, data);
								} else {
									// User clicked the Cancel button
									data.put(PROPERTY_SUCCESS, false);
									data.put(PROPERTY_CANCELLED, true);
									fireEvent(EVENT_SHARE_COMPLETE, data);
								}
							} else if (error instanceof FacebookOperationCanceledException) {
								// User clicked the "x" button
								data.put(PROPERTY_SUCCESS, false);
								data.put(PROPERTY_CANCELLED, true);
								fireEvent(EVENT_SHARE_COMPLETE, data);
							} else {
								// Generic, ex: network error
								data.put(PROPERTY_SUCCESS, false);
								data.put(PROPERTY_CANCELLED, false);
								data.put(PROPERTY_ERROR, "Error posting story");
								fireEvent(EVENT_SHARE_COMPLETE, data);
							}
						}
					})
					.build();
				} else {
					String url = (String) args.get("url");
					String imageUrl = (String) args.get("imageUrl");
					String title = (String) args.get("title");
					String description = (String) args.get("description");
					Bundle params = new Bundle();
					params.putString("name", title);
					params.putString("description", description);
					params.putString("link", url);
					params.putString("picture", imageUrl);
					feedDialog = (new WebDialog.FeedDialogBuilder(TiApplication.getInstance().getCurrentActivity(),
									Session.getActiveSession(), params))
									.setOnCompleteListener(new OnCompleteListener() {
										@Override
										public void onComplete(Bundle values,
												FacebookException error) {
											KrollDict data = new KrollDict();
											if (error == null) {
												// When the story is posted, echo the success
												// and the post Id.
												final String postId = values.getString("post_id");
												if (postId != null) {
													data.put(PROPERTY_SUCCESS, true);
													data.put(PROPERTY_CANCELLED, false);
													data.put(PROPERTY_RESULT, postId);
													fireEvent(EVENT_SHARE_COMPLETE, data);
												} else {
													// User clicked the Cancel button
													Log.d("TiAsh", "Cancelled Pressed");
													data.put(PROPERTY_SUCCESS, false);
													data.put(PROPERTY_CANCELLED, true);
													fireEvent(EVENT_SHARE_COMPLETE, data);
												}
											} else if (error instanceof FacebookOperationCanceledException) {
												// User clicked the "x" button
												data.put(PROPERTY_SUCCESS, false);
												data.put(PROPERTY_CANCELLED, true);
												fireEvent(EVENT_SHARE_COMPLETE, data);
											} else {
												// Generic, ex: network error
												data.put(PROPERTY_SUCCESS, false);
												data.put(PROPERTY_CANCELLED, false);
												data.put(PROPERTY_ERROR, "Error posting story");
												fireEvent(EVENT_SHARE_COMPLETE, data);
											}
										}

									})
									.build();
				}
				if (feedDialog != null) {
					feedDialog.show();
				}
			}
		});

	}

	@Kroll.method
	public void presentSendRequestDialog(@Kroll.argument(optional = true) final KrollDict args)
	{
		//Important note: WebDialog uses a WebView. When this is created normally via Titanium SDK, this will not work correctly.
		//This is solved by explicitly running this code on the UiThread.
		//This is mentioned in the Android docs here: https://developer.android.com/guide/webapps/migrating.html
		//If you call methods on WebView from any thread other than your app's UI thread, it can cause unexpected results. 
		//For example, if your app uses multiple threads, you can use the runOnUiThread() method to ensure your code 
		//executes on the UI thread
		TiApplication.getInstance().getCurrentActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				WebDialog requestsDialog = null;
			    if (args == null || args.isEmpty()) {
					requestsDialog = (new WebDialog.RequestsDialogBuilder(TiApplication.getAppCurrentActivity(),
									Session.getActiveSession()))
					.setOnCompleteListener(new OnCompleteListener() {
							@Override
							public void onComplete(Bundle values,
									FacebookException error) {
								KrollDict data = new KrollDict();
								if (error != null) {
									if (error instanceof FacebookOperationCanceledException) {
										data.put(PROPERTY_SUCCESS, false);
							            data.put(PROPERTY_CANCELLED, true);
										fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
									} else {
										data.put(PROPERTY_SUCCESS, false);
										data.put(PROPERTY_CANCELLED, false);
										data.put(PROPERTY_ERROR, "Network Error");
										fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
									}
								} else {
									final String requestId = values.getString("request");
									if (requestId != null) {
										data.put(PROPERTY_SUCCESS, true);
										data.put(PROPERTY_CANCELLED, false);
										data.put(PROPERTY_RESULT, requestId);
										fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
									} else {
							            data.put(PROPERTY_SUCCESS, false);
							            data.put(PROPERTY_CANCELLED, true);
										fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
									}
								}
							}
						})
						.build();
			    } else {
					String message = (String) args.get("message");
					String data = (String) args.get("data");
					Bundle params = new Bundle();
				    params.putString("message", message);
				    params.putString("data", data);
			    	requestsDialog = (
							new WebDialog.RequestsDialogBuilder(TiApplication.getAppCurrentActivity(),
									Session.getActiveSession(),
									params))
									.setOnCompleteListener(new OnCompleteListener() {
										@Override
										public void onComplete(Bundle values,
												FacebookException error) {
											KrollDict data = new KrollDict();
											if (error != null) {
												if (error instanceof FacebookOperationCanceledException) {
										            data.put(PROPERTY_SUCCESS, false);
										            data.put(PROPERTY_CANCELLED, true);
													fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
												} else {
										            data.put(PROPERTY_SUCCESS, false);
										            data.put(PROPERTY_CANCELLED, false);
													data.put(PROPERTY_ERROR, "Network Error");
													fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
												}
											} else {
												final String requestId = values.getString("request");
												if (requestId != null) {
													data.put(PROPERTY_SUCCESS, true);
													data.put(PROPERTY_CANCELLED, false);
													data.put(PROPERTY_RESULT, requestId);
													fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
												} else {
										            data.put(PROPERTY_SUCCESS, false);
										            data.put(PROPERTY_CANCELLED, true);
													fireEvent(EVENT_REQUEST_DIALOG_COMPLETE, data);
												}
											}
										}
									})
									.build();
			    }
			    if (requestsDialog != null) {
			    	requestsDialog.show();
				}
			}
		});
	}
	
	@Kroll.method
	public void requestNewReadPermissions(String[] permissions, final KrollFunction callback) {
		requestNewReadPermissions(permissions, AUDIENCE_EVERYONE, callback);
	}

	@Kroll.method
	public void requestNewReadPermissions(String[] permissions, int audienceChoice, final KrollFunction callback) {
		SessionDefaultAudience audience;
		switch(audienceChoice){
			case TiFacebookModule.AUDIENCE_NONE:
				audience = SessionDefaultAudience.NONE;
				break;
			case TiFacebookModule.AUDIENCE_ONLY_ME:
				audience = SessionDefaultAudience.ONLY_ME;
				break;
			case TiFacebookModule.AUDIENCE_FRIENDS:
				audience = SessionDefaultAudience.FRIENDS;
				break;
			default:
			case TiFacebookModule.AUDIENCE_EVERYONE:
				audience = SessionDefaultAudience.EVERYONE;
				break;
		}
		permissionCallback = callback;
		Session.getActiveSession().requestNewReadPermissions(
				new NewPermissionsRequest(TiApplication.getInstance().getCurrentActivity(), Arrays.asList(permissions)).setDefaultAudience(audience));
	}
	
	@Kroll.method
	public void requestNewPublishPermissions(String[] permissions, final KrollFunction callback) {
		requestNewPublishPermissions(permissions, AUDIENCE_EVERYONE, callback);
	}
	
	@Kroll.method
	public void requestNewPublishPermissions(String[] permissions, int audienceChoice, final KrollFunction callback) {
		SessionDefaultAudience audience;
		switch(audienceChoice){
			case TiFacebookModule.AUDIENCE_NONE:
				audience = SessionDefaultAudience.NONE;
				break;
			case TiFacebookModule.AUDIENCE_ONLY_ME:
				audience = SessionDefaultAudience.ONLY_ME;
				break;
			case TiFacebookModule.AUDIENCE_FRIENDS:
				audience = SessionDefaultAudience.FRIENDS;
				break;
			default:
			case TiFacebookModule.AUDIENCE_EVERYONE:
				audience = SessionDefaultAudience.EVERYONE;
				break;
		}
		permissionCallback = callback;
		Session.getActiveSession().requestNewPublishPermissions(
				new NewPermissionsRequest(TiApplication.getInstance().getCurrentActivity(), Arrays.asList(permissions)).setDefaultAudience(audience));		
	}

	public void setUiHelper(UiLifecycleHelper uiHelper) {
		uiLifecycleHelper = uiHelper;
	}
}


