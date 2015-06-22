package com.lesgens.minou.views;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ExpandableListView;

public class CustExpListview extends ExpandableListView {

    public CustExpListview(Context context) {
        super(context);
    }
    
    public CustExpListview(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    
    public CustExpListview(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // the value (2000) should not be fixed and be calculated
        // as follows: cell_height x root_items_count x root_items_children_count
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(2000, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            super.onDetachedFromWindow();
        } catch (IllegalArgumentException e) {
            // TODO: Workaround for http://code.google.com/p/android/issues/detail?id=22751
        }
    }
}