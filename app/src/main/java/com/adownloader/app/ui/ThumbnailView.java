package com.adownloader.app.ui;

import android.content.Context;
import android.util.AttributeSet;

/** Responsive 16:9 thumbnail, never a fixed-pixel tile. */
public final class ThumbnailView extends androidx.appcompat.widget.AppCompatImageView {
    public ThumbnailView(Context context,AttributeSet attrs){super(context,attrs);}
    @Override protected void onMeasure(int width,int height){int w=MeasureSpec.getSize(width);super.onMeasure(width,MeasureSpec.makeMeasureSpec(Math.round(w*9f/16),MeasureSpec.EXACTLY));}
}
