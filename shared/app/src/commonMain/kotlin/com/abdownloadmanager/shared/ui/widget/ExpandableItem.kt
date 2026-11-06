package com.abdownloadmanager.shared.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.abdownloadmanager.shared.util.ui.motion.conceal
import com.abdownloadmanager.shared.util.ui.motion.reveal

@Composable
fun ExpandableItem(
    isExpanded:Boolean,
    header:@Composable ()->Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
){
    Column(modifier) {
        header()
        AnimatedVisibility(isExpanded, enter = reveal(), exit = conceal()) {
            body()
        }
    }
}