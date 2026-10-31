package com.adownloader.app.ui;

import android.content.*;
import android.net.Uri;
import com.adownloader.app.R;
import com.google.android.material.snackbar.Snackbar;

public final class MediaOpener {
    private MediaOpener(){}
    public static void open(android.app.Activity activity,String uri,String mime){
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),mime==null||mime.isEmpty()?"video/*":mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(Exception error){Snackbar.make(activity.findViewById(R.id.app_root),"이 파일을 열 수 있는 플레이어가 없습니다.",Snackbar.LENGTH_LONG).show();}
    }
}
