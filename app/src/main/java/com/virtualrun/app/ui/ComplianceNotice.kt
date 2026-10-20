package com.virtualrun.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virtualrun.app.compliance.TestLabelPolicy

/**
 * 首次启动的「仅用于测试」须知。
 *
 * 必须显式选择：点外部或返回键都不关闭，避免被当成可跳过的提示。
 */
@Composable
fun TestOnlyNoticeDialog(
    onAccept: () -> Unit,
    onExit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 必须显式选择 */ },
        title = { Text("使用须知") },
        text = {
            Column {
                Text("本应用通过 Android 模拟位置（mock location）输出虚拟坐标，仅用于：", fontSize = 13.sp)
                Text("· 定位功能自测与地图渲染调试", fontSize = 13.sp)
                Text("· 轨迹回放、走查与演示", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "不得用于向校园跑、体育作业等第三方系统提交成绩。",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Text(TestLabelPolicy.STATUS_LINE, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("我已了解，仅用于测试") }
        },
        dismissButton = {
            TextButton(onClick = onExit) { Text("退出") }
        }
    )
}
