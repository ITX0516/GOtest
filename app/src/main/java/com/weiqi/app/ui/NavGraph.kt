package com.weiqi.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.weiqi.app.WeiqiApp
import com.weiqi.app.ui.analysis.AnalysisScreen
import com.weiqi.app.ui.analysis.AnalysisViewModel
import com.weiqi.app.ui.play.PlayScreen
import com.weiqi.app.ui.play.PlayViewModel
import com.weiqi.app.ui.settings.SettingsScreen
import com.weiqi.app.ui.settings.SettingsViewModel
import java.net.URLEncoder

/**
 * 顶层导航图。
 *
 * 路由：
 * - `home` 主菜单
 * - `play` 对弈
 * - `analysis?sgf={sgf}` 分析模式（可选 SGF 字符串，URL 编码）
 * - `settings` 设置
 *
 * ViewModel 通过 [viewModelFactory] 注入应用级单例（EngineManager / SoundManager 等）。
 */
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val app = WeiqiApp.instance

    NavHost(navController = navController, startDestination = "home") {

        composable("home") {
            HomeScreen(
                onPlay = { navController.navigate("play") },
                onAnalyze = { navController.navigate("analysis") },
                onSettings = { navController.navigate("settings") }
            )
        }

        composable("play") {
            val vm: PlayViewModel = viewModel(
                factory = remember { PlayViewModelFactory(app) }
            )
            PlayScreen(
                viewModel = vm,
                onOpenSettings = { navController.navigate("settings") },
                onOpenAnalysis = { sgf ->
                    val encoded = URLEncoder.encode(sgf, "UTF-8")
                    navController.navigate("analysis?sgf=$encoded")
                },
                onImportSgf = { navController.navigate("analysis") }
            )
        }

        composable(
            route = "analysis?sgf={sgf}",
            arguments = listOf(navArgument("sgf") {
                type = NavType.StringType
                defaultValue = ""
                nullable = true
            })
        ) { backStackEntry ->
            val sgf = backStackEntry.arguments?.getString("sgf").orEmpty()
            val vm: AnalysisViewModel = viewModel(
                factory = remember { AnalysisViewModelFactory(app, sgf) }
            )
            AnalysisScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("settings") }
            )
        }

        composable("settings") {
            val vm: SettingsViewModel = viewModel(
                factory = remember { SettingsViewModelFactory(app) }
            )
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
