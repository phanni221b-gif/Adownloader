package com.adownloader.app.ui;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.*;
import com.adownloader.app.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import java.util.*;

public final class SettingsController {
    private final AppCompatActivity activity;private final SharedPreferences preferences;
    public SettingsController(AppCompatActivity activity){
        this.activity=activity;preferences=activity.getSharedPreferences("appearance",0);
        activity.findViewById(R.id.setting_popups).setOnClickListener(v->showSites());
        activity.findViewById(R.id.setting_reset_popups).setOnClickListener(v->UiSheets.action(activity,R.drawable.ic_block,"팝업 허용 기록 초기화","모든 사이트에서 새 창을 다시 차단합니다.","취소","초기화",()->{activity.getSharedPreferences("popup_policy",0).edit().clear().apply();Snackbar.make(activity.findViewById(R.id.app_root),"팝업 허용 기록을 초기화했습니다.",Snackbar.LENGTH_SHORT).show();},null,null));
        activity.findViewById(R.id.setting_theme).setOnClickListener(this::themeMenu);
        activity.findViewById(R.id.setting_background).setOnClickListener(v->activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName())));
        String version="";try{version=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;}catch(Exception ignored){}
        ((TextView)activity.findViewById(R.id.app_version)).setText("버전 "+version);refresh();
    }
    public static int theme(Context context){return context.getSharedPreferences("appearance",0).getInt("theme",AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);}
    public void refresh(){
        int mode=theme(activity);((MaterialButton)activity.findViewById(R.id.setting_theme)).setText("테마 · "+(mode==AppCompatDelegate.MODE_NIGHT_YES?"다크":mode==AppCompatDelegate.MODE_NIGHT_NO?"라이트":"시스템 설정"));
        boolean notifications=activity.getSystemService(NotificationManager.class).areNotificationsEnabled();
        ((TextView)activity.findViewById(R.id.background_state)).setText("화면을 닫아도 다운로드가 계속됩니다.\n완료 알림 "+(notifications?"켜짐":"꺼짐 · 탭해서 설정"));
    }
    private void themeMenu(View anchor){
        PopupMenu menu=new PopupMenu(activity,anchor);String[] labels={"시스템 설정 따르기","라이트","다크"};int[] modes={AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,AppCompatDelegate.MODE_NIGHT_NO,AppCompatDelegate.MODE_NIGHT_YES};
        for(int i=0;i<labels.length;i++)menu.getMenu().add(0,i,i,labels[i]).setCheckable(true).setChecked(theme(activity)==modes[i]);
        menu.setOnMenuItemClickListener(item->{int mode=modes[item.getItemId()];preferences.edit().putInt("theme",mode).apply();activity.getDelegate().setLocalNightMode(mode);refresh();return true;});menu.show();
    }
    private void showSites(){
        SharedPreferences sites=activity.getSharedPreferences("popup_policy",0);List<String> keys=new ArrayList<>();sites.getAll().forEach((key,value)->{if(Boolean.TRUE.equals(value))keys.add(key);});Collections.sort(keys);
        View content=activity.getLayoutInflater().inflate(R.layout.sheet_list,null);((TextView)content.findViewById(R.id.sheet_title)).setText("팝업 허용 사이트");((TextView)content.findViewById(R.id.sheet_subtitle)).setText("새 창을 요청한 사이트 기준으로 적용합니다.");
        RecyclerView list=content.findViewById(R.id.sheet_list);list.setLayoutManager(new LinearLayoutManager(activity));
        BottomSheetDialog dialog=UiSheets.show(activity,content,true);content.findViewById(R.id.sheet_close).setOnClickListener(v->dialog.dismiss());
        Runnable empty=()->{View view=content.findViewById(R.id.sheet_empty);view.setVisibility(keys.isEmpty()?View.VISIBLE:View.GONE);((TextView)view.findViewById(R.id.empty_title)).setText("허용한 사이트가 없습니다.");((TextView)view.findViewById(R.id.empty_message)).setText("모든 사이트의 새 창을 기본으로 차단합니다.");};empty.run();
        list.setAdapter(new RecyclerView.Adapter<SiteHolder>(){
            @Override public SiteHolder onCreateViewHolder(ViewGroup parent,int type){return new SiteHolder(activity.getLayoutInflater().inflate(R.layout.item_media,parent,false));}
            @Override public int getItemCount(){return keys.size();}
            @Override public void onBindViewHolder(SiteHolder holder,int position){String key=keys.get(position);((TextView)holder.itemView.findViewById(R.id.media_title)).setText(key);((TextView)holder.itemView.findViewById(R.id.media_type)).setText("허용");holder.itemView.findViewById(R.id.media_host).setVisibility(View.GONE);holder.itemView.findViewById(R.id.media_metadata).setVisibility(View.GONE);holder.itemView.findViewById(R.id.media_details).setVisibility(View.GONE);MaterialButton remove=holder.itemView.findViewById(R.id.media_download);remove.setText("허용 해제");remove.setIconResource(R.drawable.ic_block);remove.setOnClickListener(v->{sites.edit().remove(key).apply();keys.remove(key);notifyDataSetChanged();empty.run();});}
        });
    }
    private static final class SiteHolder extends RecyclerView.ViewHolder{SiteHolder(View view){super(view);}}
}
