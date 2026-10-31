package com.adownloader.app.browser;

import android.app.*;
import android.content.*;
import android.net.Uri;
import java.net.URI;
import java.util.*;

/** Default-deny new windows and external schemes, with per-initiating-site grants. */
public final class PopupController {
    public interface Opener { void open(String url); }
    private final Activity activity;private final Opener opener;private final SharedPreferences prefs;
    private boolean showing;
    private String lastRequest=""; private long lastShown;
    public PopupController(Activity activity,Opener opener){this.activity=activity;this.opener=opener;prefs=activity.getSharedPreferences("popup_policy",0);}
    public void clearAllowed(){prefs.edit().clear().apply();}
    public void onPopupRequested(String url,String source){
        if(url==null || activity.isFinishing() || activity.isDestroyed())return;
        Uri uri=Uri.parse(url);String scheme=uri.getScheme();boolean web="https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme);
        String origin=origin(source),target=web?uri.getHost():scheme;
        if(!web && !Arrays.asList("intent","market","mailto","tel","sms").contains(scheme))return;
        if(web && !origin.isEmpty() && prefs.getBoolean(origin,false)){opener.open(url);return;}
        String request=origin+"|"+url;
        long now=android.os.SystemClock.elapsedRealtime();
        if(showing||(request.equals(lastRequest)&&now-lastShown<2500))return;
        showing=true;lastRequest=request;lastShown=now;
        com.google.android.material.bottomsheet.BottomSheetDialog shown=com.adownloader.app.ui.UiSheets.action(
            activity,com.adownloader.app.R.drawable.ic_block,web?"새 창을 열려고 합니다":"외부 앱을 열려고 합니다",
            "요청 사이트 · "+origin+"\n이동할 곳 · "+(target==null?"외부 앱":target),"차단","한 번 열기",()->open(url,web),
            web&&!origin.isEmpty()?"이 사이트에서 항상 허용":null,()->{prefs.edit().putBoolean(origin,true).apply();opener.open(url);});
        shown.setOnDismissListener(d->showing=false);
    }
    private void open(String url,boolean web){
        if(web){opener.open(url);return;}
        try{
            Intent intent=Intent.parseUri(url,Intent.URI_INTENT_SCHEME);intent.addCategory(Intent.CATEGORY_BROWSABLE);intent.setComponent(null);intent.setSelector(null);
            intent.removeExtra("browser_fallback_url");activity.startActivity(intent);
        }catch(Exception ignored){android.widget.Toast.makeText(activity,"열 수 있는 외부 앱이 없습니다",android.widget.Toast.LENGTH_SHORT).show();}
    }
    private String origin(String url){try{URI u=URI.create(url);return u.getHost()==null?"":u.getScheme()+"://"+u.getHost()+(u.getPort()<0?"":":"+u.getPort());}catch(Exception ignored){return "";}}
}
