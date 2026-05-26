package com.biometrix.operator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.biometrix.operator.presentation.navigation.AppNavigation
import com.biometrix.operator.ui.theme.BioMetrixOperatorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BioMetrixOperatorTheme {
                AppNavigation()
            }
        }
    }
}
