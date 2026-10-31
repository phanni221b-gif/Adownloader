package com.adownloader.app.ui;

import android.app.Activity;
import android.view.*;
import android.widget.TextView;
import com.adownloader.app.R;
import com.google.android.material.bottomsheet.*;
import com.google.android.material.button.MaterialButton;

/** Shared, inset-aware sheets. Width follows the window, capped on expanded Fold screens. */
public final class UiSheets {
    private UiSheets() {}
    public static BottomSheetDialog show(Activity activity, View content, boolean tall) {
        BottomSheetDialog dialog=new BottomSheetDialog(activity);
        int left=content.getPaddingLeft(),top=content.getPaddingTop(),right=content.getPaddingRight(),bottom=content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            v.setPadding(left+safe.left,top,right+safe.right,bottom+safe.bottom);return insets;
        });
        dialog.setContentView(content);
        int[] previousBounds={-1,-1,-1};
        Runnable resize=()->{
            android.graphics.Rect bounds=activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            int density=activity.getResources().getDisplayMetrics().densityDpi;
            if(previousBounds[0]==bounds.width()&&previousBounds[1]==bounds.height()&&previousBounds[2]==density)return;
            previousBounds[0]=bounds.width();previousBounds[1]=bounds.height();previousBounds[2]=density;
            BottomSheetBehavior<?> behavior=dialog.getBehavior();
            behavior.setMaxWidth(Math.min(bounds.width(),dp(activity,640)));
            behavior.setMaxHeight((int)(bounds.height()*0.88f));
            if(tall)content.getLayoutParams().height=(int)(bounds.height()*0.78f);
            content.requestLayout();
        };
        content.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->resize.run());
        dialog.setOnShowListener(d->{
            resize.run();
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            dialog.getBehavior().setSkipCollapsed(true);content.requestApplyInsets();
        });
        dialog.show();return dialog;
    }
    public static BottomSheetDialog action(Activity activity,int icon,String title,String message,String cancel,String confirm,Runnable accepted,String extra,Runnable extraAction) {
        View content=activity.getLayoutInflater().inflate(R.layout.sheet_action,null);
        ((android.widget.ImageView)content.findViewById(R.id.action_icon)).setImageResource(icon);
        ((TextView)content.findViewById(R.id.action_title)).setText(title);
        ((TextView)content.findViewById(R.id.action_message)).setText(message);
        BottomSheetDialog dialog=show(activity,content,false);
        MaterialButton no=content.findViewById(R.id.action_cancel),yes=content.findViewById(R.id.action_confirm),other=content.findViewById(R.id.action_extra);
        no.setText(cancel);no.setOnClickListener(v->dialog.dismiss());
        if(confirm==null)yes.setVisibility(View.GONE);else{yes.setText(confirm);yes.setOnClickListener(v->{dialog.dismiss();if(accepted!=null)accepted.run();});}
        if(extra!=null){other.setVisibility(View.VISIBLE);other.setText(extra);other.setOnClickListener(v->{dialog.dismiss();if(extraAction!=null)extraAction.run();});}
        return dialog;
    }
    public static int dp(android.content.Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
