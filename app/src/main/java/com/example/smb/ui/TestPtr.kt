package com.example.smb.ui

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun TestPtr() {
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = {}
    ) {
        Text("Test")
    }
}
