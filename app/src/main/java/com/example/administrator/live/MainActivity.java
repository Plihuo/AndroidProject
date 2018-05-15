package com.example.administrator.live;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.example.administrator.mybooklibrary.R;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private Button mLiveButton;
    private EditText mLiveUrl;
    public static final String LIVE_URL="live_url";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
    }
    private void initUI(){
        mLiveButton = findViewById(R.id.live_button);
        mLiveUrl = findViewById(R.id.live_url);
        mLiveUrl.setText("rtmp://192.168.0.100:1935/live/12345678");
        mLiveButton.setOnClickListener(this);
    }
    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.live_button:
                startLiveActivity();
                break;
        }
    }
    private void startLiveActivity(){
        Intent intent  = new Intent(this,LiveActivity.class);
        intent.putExtra(LIVE_URL,mLiveUrl.getText().toString());
        startActivity(intent);
    }
}
