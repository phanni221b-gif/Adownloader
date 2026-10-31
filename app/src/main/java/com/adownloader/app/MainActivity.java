package com.adownloader.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.*;
import androidx.appcompat.widget.PopupMenu;
import com.adownloader.app.browser.*;
import com.adownloader.app.media.MediaDetector;
import com.adownloader.app.ui.*;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoView;

/** UI shell only. Navigation, detection and downloading retain the verified v0.4 engines. */
public final class MainActivity extends AppCompatActivity implements BrowserSessionManager.Listener,MediaDetector.Listener {
    private BrowserSessionManager browser;
    private PopupController popups;
    private MediaDetector detector;
    private RecordsController records;
    private SettingsController settings;
    private MediaSheetController mediaSheet;
    private GeckoView geckoView;
    private View root;
    private EditText address;
    private ImageButton back,forward;
    private MaterialButton media;
    private LinearProgressIndicator progress;
    private String currentUrl="https://www.google.com";
    private boolean initialLoaded,canBack,canForward,dark,started;
    private float shellDensity,shellFontScale;
    private int selected=R.id.nav_browser,pageProgress=100,mediaCount;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean detectionNoticePending;

    @Override protected void onCreate(Bundle state){
        getDelegate().setLocalNightMode(SettingsController.theme(this));
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        geckoView=new GeckoView(this);
        if(state!=null)selected=state.getInt("selected",R.id.nav_browser);
        bindShell();
        detector=new MediaDetector(this);
        browser=new BrowserSessionManager(this,geckoView,this);
        popups=new PopupController(this,browser::load);
        new MediaExtensionManager(browser.getRuntime(),detector,new MediaExtensionManager.StatusListener(){
            @Override public void onExtensionReady(){runOnUiThread(()->loadInitialPage());}
            @Override public void onExtensionError(String message){runOnUiThread(()->{loadInitialPage();notice("영상 감지를 준비하지 못했습니다. 앱을 다시 열어 주세요.");});}
        }).install();
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){if(selected!=R.id.nav_browser){selectPage(R.id.nav_browser);return;}if(browser!=null&&browser.canGoBack())browser.goBack();else finish();}
        });
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},50);
    }
    private void loadInitialPage(){
        if(initialLoaded||isDestroyed())return;initialLoaded=true;
        Uri incoming=getIntent().getData();
        if(incoming!=null&&("https".equals(incoming.getScheme())||"http".equals(incoming.getScheme()))){currentUrl=incoming.toString();browser.load(currentUrl);return;}
        currentUrl=getPreferences(MODE_PRIVATE).getString("last_url",currentUrl);
        if(!browser.restore(getPreferences(MODE_PRIVATE).getString("session",null)))browser.load(currentUrl);
    }
    private void bindShell(){
        if(records!=null)records.close();if(mediaSheet!=null)mediaSheet.close();
        if(geckoView.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)geckoView.getParent()).removeView(geckoView);
        setContentView(R.layout.activity_main);root=findViewById(R.id.app_root);
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        shellDensity=getResources().getDisplayMetrics().density;shellFontScale=getResources().getConfiguration().fontScale;
        getWindow().getInsetsController().setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        ((FrameLayout)findViewById(R.id.gecko_host)).addView(geckoView,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());boolean keyboard=insets.isVisible(WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
            findViewById(R.id.main_navigation).setVisibility(keyboard?View.GONE:View.VISIBLE);return WindowInsets.CONSUMED;
        });
        address=findViewById(R.id.address_bar);address.setText(currentUrl);
        address.setOnEditorActionListener((view,action,event)->{
            if(action==EditorInfo.IME_ACTION_GO||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN)){
                navigate(address.getText().toString());address.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);return true;
            }return false;
        });
        back=findViewById(R.id.browser_back);forward=findViewById(R.id.browser_forward);back.setEnabled(canBack);forward.setEnabled(canForward);
        back.setOnClickListener(v->browser.goBack());forward.setOnClickListener(v->browser.goForward());findViewById(R.id.browser_refresh).setOnClickListener(v->browser.reload());
        findViewById(R.id.browser_menu).setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,v);menu.inflate(R.menu.browser_menu);menu.setOnMenuItemClickListener(item->{int id=item.getItemId();if(id==R.id.menu_home)browser.load("https://www.google.com");else if(id==R.id.menu_reload)browser.reload();else if(id==R.id.menu_clear_media)detector.reset();else selectPage(R.id.nav_settings);return true;});menu.show();});
        media=findViewById(R.id.media_action);updateMediaBadge();media.setOnClickListener(v->mediaSheet.show(detector.snapshot()));
        progress=findViewById(R.id.page_progress);progress.setProgressCompat(pageProgress,false);progress.setVisibility(pageProgress>=100?View.INVISIBLE:View.VISIBLE);
        records=new RecordsController(this);settings=new SettingsController(this);mediaSheet=new MediaSheetController(this,this::startDownload);
        BottomNavigationView navigation=findViewById(R.id.main_navigation);navigation.setOnItemSelectedListener(item->{showPage(item.getItemId());return true;});navigation.setSelectedItemId(selected);showPage(selected);root.requestApplyInsets();
    }
    private void navigate(String raw){
        String value=raw==null?"":raw.trim();if(value.isEmpty())return;
        if(!(value.startsWith("https://")||value.startsWith("http://")))value=!value.contains(" ")&&value.contains(".")?"https://"+value:"https://www.google.com/search?q="+Uri.encode(value);
        browser.load(value);
    }
    private void selectPage(int id){((BottomNavigationView)findViewById(R.id.main_navigation)).setSelectedItemId(id);}
    private void showPage(int id){
        selected=id;findViewById(R.id.browser_page).setVisibility(id==R.id.nav_browser?View.VISIBLE:View.GONE);findViewById(R.id.downloads_page).setVisibility(id==R.id.nav_downloads?View.VISIBLE:View.GONE);findViewById(R.id.library_page).setVisibility(id==R.id.nav_library?View.VISIBLE:View.GONE);findViewById(R.id.settings_page).setVisibility(id==R.id.nav_settings?View.VISIBLE:View.GONE);
        if(browser!=null)browser.setActive(started&&id==R.id.nav_browser);if(id==R.id.nav_downloads||id==R.id.nav_library)records.refresh(false);if(id==R.id.nav_settings)settings.refresh();
    }
    private void startDownload(MediaCandidate item){
        Intent intent=new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_DOWNLOAD).putExtra(DownloadService.EXTRA_URL,item.getUrl()).putExtra(DownloadService.EXTRA_TYPE,item.getType().name()).putExtra(DownloadService.EXTRA_HEADERS,new JSONObject(item.getHeaders()).toString());
        startForegroundService(intent);Snackbar.make(root,"다운로드를 시작했습니다.",Snackbar.LENGTH_SHORT).setAnchorView(findViewById(R.id.main_navigation)).setAction("보기",v->selectPage(R.id.nav_downloads)).show();
    }
    private void updateMediaBadge(){media.setText(mediaCount==0?"":Integer.toString(mediaCount));media.setContentDescription(mediaCount==0?"감지된 미디어":"감지된 미디어 "+mediaCount+"개");}
    private void notice(String text){if(root!=null&&!isDestroyed())Snackbar.make(root,text,Snackbar.LENGTH_SHORT).setAnchorView(findViewById(R.id.main_navigation)).show();}
    @Override public void onLocationChanged(String url){runOnUiThread(()->{if(detector!=null&&!Uri.parse(currentUrl).buildUpon().fragment(null).build().equals(Uri.parse(url).buildUpon().fragment(null).build()))detector.reset();currentUrl=url;if(!address.hasFocus())address.setText(url);});}
    @Override public void onNavigationStateChanged(boolean canBack,boolean canForward){this.canBack=canBack;this.canForward=canForward;runOnUiThread(()->{back.setEnabled(canBack);forward.setEnabled(canForward);});}
    @Override public void onPageProgress(int value){pageProgress=value;runOnUiThread(()->{progress.setProgressCompat(value,true);progress.setVisibility(value>=100?View.INVISIBLE:View.VISIBLE);});}
    @Override public void onPopupRequested(String url){runOnUiThread(()->popups.onPopupRequested(url,currentUrl));}
    @Override public void onBrowserCrashed(){runOnUiThread(()->UiSheets.action(this,R.drawable.ic_browser,"페이지가 종료되었습니다","새 세션에서 현재 페이지를 다시 열 수 있습니다.","닫기","다시 열기",()->browser.recover(currentUrl),null,null));}
    @Override public void onCandidateAdded(MediaCandidate item,int count){runOnUiThread(()->{boolean first=mediaCount==0&&count>0;mediaCount=count;updateMediaBadge();if(first&&!detectionNoticePending){detectionNoticePending=true;handler.postDelayed(()->{detectionNoticePending=false;if(mediaCount>0&&selected==R.id.nav_browser)notice("동영상 "+mediaCount+"개 감지됨");},700);}});}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);boolean now=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;if(root!=null&&(now!=dark||shellDensity!=getResources().getDisplayMetrics().density||shellFontScale!=getResources().getConfiguration().fontScale))bindShell();else if(records!=null){records.resize();root.requestApplyInsets();}}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("selected",selected);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();started=true;if(browser!=null)browser.setActive(selected==R.id.nav_browser);if(records!=null)records.refresh(false);if(settings!=null)settings.refresh();}
    @Override protected void onPause(){started=false;if(browser!=null){browser.setActive(false);getPreferences(MODE_PRIVATE).edit().putString("last_url",currentUrl).apply();browser.save(state->getPreferences(MODE_PRIVATE).edit().putString("session",state).apply());}super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(records!=null)records.close();if(mediaSheet!=null)mediaSheet.close();if(browser!=null)browser.close();super.onDestroy();}
}
