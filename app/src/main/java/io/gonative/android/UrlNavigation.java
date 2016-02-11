package io.gonative.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.Pair;
import android.webkit.CookieSyncManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.gonative.android.library.AppConfig;

public class UrlNavigation {
    public static final String STARTED_LOADING_MESSAGE = "io.gonative.android.webview.started";
    public static final String FINISHED_LOADING_MESSAGE = "io.gonative.android.webview.finished";
    public static final String CLEAR_POOLS_MESSAGE = "io.gonative.android.webview.clearPools";

    private static final String TAG = UrlNavigation.class.getName();

    public static final int DEFAULT_HTML_SIZE = 10 * 1024; // 10 kilobytes

	private MainActivity mainActivity;
    private String profilePickerExec;
    private String analyticsExec;
    private String dynamicUpdateExec;
    private String currentWebviewUrl;
    private String interceptHtmlUrl; // used by GoNativeXWalkResourceClient

    private boolean mVisitedLoginOrSignup = false;

	public UrlNavigation(MainActivity activity) {
		super();
		this.mainActivity = activity;

        AppConfig appConfig = AppConfig.getInstance(mainActivity);

        // profile picker
        if (appConfig.profilePickerJS != null) {
            this.profilePickerExec = "gonative_profile_picker.parseJson(eval("
                    + LeanUtils.jsWrapString(appConfig.profilePickerJS)
                    + "))";
        }

        // analytics
        if (appConfig.analytics) {
            String distribution = (String)Installation.getInfo(mainActivity).get("distribution");
            int idsite;
            if (distribution != null && (distribution.equals("playstore") || distribution.equals("amazon")))
                idsite = appConfig.idsite_prod;
            else idsite = appConfig.idsite_test;

            this.analyticsExec = String.format("var _paq = _paq || [];\n" +
                    "  _paq.push(['trackPageView']);\n" +
                    "  _paq.push(['enableLinkTracking']);\n" +
                    "  (function() {\n" +
                    "    var u = 'https://analytics.gonative.io/';\n" +
                    "    _paq.push(['setTrackerUrl', u+'piwik.php']);\n" +
                    "    _paq.push(['setSiteId', %d]);\n" +
                    "    var d=document, g=d.createElement('script'), s=d.getElementsByTagName('script')[0]; g.type='text/javascript';\n" +
                    "    g.defer=true; g.async=true; g.src=u+'piwik.js'; s.parentNode.insertBefore(g,s);\n" +
                    "  })();", idsite);
        }

        // dynamic config updates
        if (appConfig.updateConfigJS != null) {
            this.dynamicUpdateExec = "gonative_dynamic_update.parseJson(eval("
                    + LeanUtils.jsWrapString(appConfig.updateConfigJS)
                    + "))";
        }
	}
	
	
	private boolean isInternalUri(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }

        AppConfig appConfig = AppConfig.getInstance(mainActivity);
        String urlString = uri.toString();

        // first check regexes
        ArrayList<Pattern> regexes = appConfig.regexInternalExternal;
        ArrayList<Boolean> isInternal = appConfig.regexIsInternal;
        if (regexes != null) {
            for (int i = 0; i < regexes.size(); i++) {
                Pattern regex = regexes.get(i);
                if (regex.matcher(urlString).matches()) {
                    return isInternal.get(i);
                }
            }
        }

        String host = uri.getHost();
        String initialHost = appConfig.initialHost;

        return host != null &&
                (host.equals(initialHost) || host.endsWith("." + initialHost));
    }

    public boolean shouldOverrideUrlLoading(GoNativeWebviewInterface view, String url) {
        return shouldOverrideUrlLoading(view, url, false);
    }

    // noAction to skip stuff like opening url in external browser, higher nav levels, etc.
    public boolean shouldOverrideUrlLoadingNoIntercept(final GoNativeWebviewInterface view, final String url, final boolean noAction) {
//		Log.d(TAG, "shouldOverrideUrl: " + url);

        // return if url is null (can happen if clicking refresh when there is no page loaded)
        if (url == null)
            return false;

        // return if loading from local assets
        if (url.startsWith("file:///android_asset/")) return false;

        // checkLoginSignup might be false when returning from login screen with loginIsFirstPage
        boolean checkLoginSignup = ((LeanWebView)view).checkLoginSignup();
        ((LeanWebView)view).setCheckLoginSignup(true);

        Uri uri = Uri.parse(url);

        if (uri.getScheme() != null && uri.getScheme().equals("gonative-bridge")) {
            if (noAction) return true;

            try {
                String json = uri.getQueryParameter("json");

                JSONArray parsedJson = new JSONArray(json);
                for (int i = 0; i < parsedJson.length(); i++) {
                    JSONObject entry = parsedJson.optJSONObject(i);
                    if (entry == null) continue;

                    String command = entry.optString("command");
                    if (command == null) continue;

                    if (command.equals("pop")) {
                        if (!mainActivity.isRoot()) mainActivity.finish();
                    } else if (command.equals("clearPools")) {
                        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(
                                new Intent(UrlNavigation.CLEAR_POOLS_MESSAGE));
                    }
                }
            } catch (Exception e) {
                // do nothing
            }

            return true;
        }

        final AppConfig appConfig = AppConfig.getInstance(mainActivity);

        // check redirects
        if (appConfig.redirects != null) {
            String to = appConfig.redirects.get(url);
            if (to == null) to = appConfig.redirects.get("*");
            if (to != null && !to.equals(url)) {
                if (noAction) return true;

                final String destination = to;
                mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mainActivity.loadUrl(destination);
                    }
                });
                return true;
            }
        }

        if(checkLoginSignup &&
                appConfig.loginUrl != null &&
                LeanUtils.urlsMatchOnPath(url, appConfig.loginUrl)){
            if (noAction) return true;

            mainActivity.launchWebForm("login", "Log In");
            return true;
        }
        else if(checkLoginSignup &&
                appConfig.signupUrl != null &&
                LeanUtils.urlsMatchOnPath(url, appConfig.signupUrl)) {
            if (noAction) return true;

            mainActivity.launchWebForm("signup", "Sign Up");
            return true;
        }

        if (!isInternalUri(uri)){
            if (noAction) return true;

            // launch browser
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            try {
                mainActivity.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, e.getMessage(), e);
            }

            return true;
        }

        // Starting here, we are going to load the request, but possibly in a
        // different activity depending on the structured nav level

        int currentLevel = mainActivity.getUrlLevel();
        int newLevel = mainActivity.urlLevelForUrl(url);
        if (currentLevel >= 0 && newLevel >= 0) {
            if (newLevel > currentLevel) {
                if (noAction) return true;

                // new activity
                Intent intent = new Intent(mainActivity.getBaseContext(), MainActivity.class);
                intent.putExtra("isRoot", false);
                intent.putExtra("url", url);
                intent.putExtra("parentUrlLevel", currentLevel);
                intent.putExtra("postLoadJavascript", mainActivity.postLoadJavascript);
                mainActivity.startActivityForResult(intent, MainActivity.REQUEST_WEB_ACTIVITY);

                mainActivity.postLoadJavascript = null;
                mainActivity.postLoadJavascriptForRefresh = null;

                return true;
            }
            else if (newLevel < currentLevel && newLevel <= mainActivity.getParentUrlLevel()) {
                if (noAction) return true;

                // pop activity
                Intent returnIntent = new Intent();
                returnIntent.putExtra("url", url);
                returnIntent.putExtra("urlLevel", newLevel);
                returnIntent.putExtra("postLoadJavascript", mainActivity.postLoadJavascript);
                mainActivity.setResult(Activity.RESULT_OK, returnIntent);
                mainActivity.finish();
                return true;
            }
        }

        // Starting here, the request will be loaded in this activity.
        if (newLevel >= 0) {
            mainActivity.setUrlLevel(newLevel);
        }

        final String newTitle = mainActivity.titleForUrl(url);
        if (newTitle != null) {
            if (!noAction) {
                mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mainActivity.setTitle(newTitle);
                    }
                });
            }
        }

        // nav title image
        if (!noAction) {
            mainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mainActivity.showLogoInActionBar(appConfig.shouldShowNavigationTitleImageForUrl(url));
                }
            });
        }

        // check to see if the webview exists in pool.
        Pair<GoNativeWebviewInterface, WebViewPoolDisownPolicy> pair = WebViewPool.getInstance().webviewForUrl(url);
        final GoNativeWebviewInterface poolWebview = pair.first;
        WebViewPoolDisownPolicy poolDisownPolicy = pair.second;

        if (noAction && poolWebview != null) return true;

        if (poolWebview != null && poolDisownPolicy == WebViewPoolDisownPolicy.Always) {
            this.mainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mainActivity.switchToWebview(poolWebview, true);
                    mainActivity.checkNavigationForPage(url);
                }
            });
            WebViewPool.getInstance().disownWebview(poolWebview);
            LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(new Intent(UrlNavigation.FINISHED_LOADING_MESSAGE));
            return true;
        }

        if (poolWebview != null && poolDisownPolicy == WebViewPoolDisownPolicy.Never) {
            this.mainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mainActivity.switchToWebview(poolWebview, true);
                    mainActivity.checkNavigationForPage(url);
                }
            });
            return true;
        }

        if (poolWebview != null && poolDisownPolicy == WebViewPoolDisownPolicy.Reload &&
                !LeanUtils.urlsMatchOnPath(url, this.currentWebviewUrl)) {
            this.mainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mainActivity.switchToWebview(poolWebview, true);
                    mainActivity.checkNavigationForPage(url);
                }
            });
            return true;
        }

        if (this.mainActivity.isPoolWebview) {
            // if we are here, either the policy is reload and we are reloading the page, or policy is never but we are going to a different page. So take ownership of the webview.
            WebViewPool.getInstance().disownWebview(view);
            this.mainActivity.isPoolWebview = false;
        }

        return false;
    }

	public boolean shouldOverrideUrlLoading(GoNativeWebviewInterface view, String url, boolean isReload) {
        if (url == null) return false;

        boolean shouldOverride = shouldOverrideUrlLoadingNoIntercept(view, url, false);
        if (shouldOverride) return true;

        // intercept html
        this.interceptHtmlUrl = null;
        if (AppConfig.getInstance(mainActivity).interceptHtml) {
            try {
                URL parsedUrl = new URL(url);
                if (parsedUrl.getProtocol().equals("http") || parsedUrl.getProtocol().equals("https")) {
                    this.interceptHtmlUrl = url;

                    if (!view.isCrosswalk()) {
                        mainActivity.setProgress(0);
                        new WebviewInterceptTask(this.mainActivity, this).execute(new WebviewInterceptTask.WebviewInterceptParams(view, parsedUrl, isReload));
                        mainActivity.hideWebview();
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        mainActivity.hideWebview();
        return false;
    }

	public void onPageStarted(String url) {
//        Log.d(TAG, "onpagestarted " + url);
        UrlInspector.getInstance().inspectUrl(url);
		Uri uri = Uri.parse(url);

        // reload menu if internal url
        if (AppConfig.getInstance(mainActivity).loginDetectionUrl != null && isInternalUri(uri)) {
            mainActivity.updateMenu();
        }

        // check ready status
        mainActivity.startCheckingReadyStatus();

        // send broadcast message
        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(new Intent(UrlNavigation.STARTED_LOADING_MESSAGE));
    }

    public void showWebViewImmediately() {
        mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mainActivity.showWebviewImmediately();
            }
        });
    }

	public void onPageFinished(GoNativeWebviewInterface view, String url) {
//        Log.d(TAG, "onpagefinished " + url);

        this.currentWebviewUrl = url;

        AppConfig appConfig = AppConfig.getInstance(mainActivity);
        if (url != null && appConfig.ignorePageFinishedRegexes != null) {
            for (Pattern pattern: appConfig.ignorePageFinishedRegexes) {
                if (pattern.matcher(url).matches()) return;
            }
        }

        mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mainActivity.showWebview();
            }
        });

        UrlInspector.getInstance().inspectUrl(url);

		Uri uri = Uri.parse(url);		
		if (isInternalUri(uri)){
            CookieSyncManager.getInstance().sync();
		}

        if (appConfig.loginDetectionUrl != null) {
            if (mVisitedLoginOrSignup){
                mainActivity.updateMenu();
            }

            mVisitedLoginOrSignup = LeanUtils.urlsMatchOnPath(url, appConfig.loginUrl) ||
                    LeanUtils.urlsMatchOnPath(url, appConfig.signupUrl);
        }

        // dynamic config updater
        if (this.dynamicUpdateExec != null) {
            view.runJavascript(this.dynamicUpdateExec);
        }

        // profile picker
        if (this.profilePickerExec != null) {
            view.runJavascript(this.profilePickerExec);
        }

        // analytics
        if (this.analyticsExec != null) {
            view.runJavascript(this.analyticsExec);
        }

        // tabs
        mainActivity.checkNavigationForPage(url);

        // post-load javascript
        if (mainActivity.postLoadJavascript != null) {
            String js = mainActivity.postLoadJavascript;
            mainActivity.postLoadJavascript = null;
            mainActivity.runJavascript(js);
        }
		
        // send broadcast message
        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(new Intent(UrlNavigation.FINISHED_LOADING_MESSAGE));

	}

    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        if (!isReload && !url.equals("file:///android_asset/offline.html")) {
            mainActivity.addToHistory(url);
        }
    }
	
	public void onReceivedError(GoNativeWebviewInterface view, int errorCode, String description, String failingUrl){
        mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mainActivity.showWebview();
            }
        });

		// first check connectivity
		if (!mainActivity.isConnected()){
            view.loadUrlDirect("file:///android_asset/offline.html");
		}
	}

    public String getCurrentWebviewUrl() {
        return currentWebviewUrl;
    }

    public void setCurrentWebviewUrl(String currentWebviewUrl) {
        this.currentWebviewUrl = currentWebviewUrl;
    }

    public String getInterceptHtmlUrl() {
        return interceptHtmlUrl;
    }

    public Intent createFileChooserIntent(String[] mimetypespec) {
        boolean isMultipleTypes = false;
        ArrayList<String> mimeTypes = new ArrayList<String>();
        for (String spec : mimetypespec) {
            String[] splitSpec = spec.split("[,;\\s]");
            for (String s : splitSpec) {
                if (s.startsWith(".")) {
                    String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(s.substring(1));
                    if (t != null) mimeTypes.add(t);
                } else if (s.contains("/")) {
                    mimeTypes.add(s);
                }
            }
        }

        if (mimeTypes.isEmpty()) mimeTypes.add("*/*");

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        if (mimeTypes.size() == 1) {
            intent.setType(mimeTypes.get(0));
        } else {
            intent.setType("*/*");

            // If running kitkat or later, then we can specify multiple mime types
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray());
            }
        }

        return intent;
    }
}
