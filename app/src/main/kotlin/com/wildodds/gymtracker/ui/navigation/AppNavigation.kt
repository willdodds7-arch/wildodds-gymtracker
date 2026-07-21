@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.wildodds.gymtracker.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wildodds.gymtracker.ui.habits.HabitsScreen
import com.wildodds.gymtracker.ui.history.HistoryScreen
import com.wildodds.gymtracker.ui.home.HomeScreen
import com.wildodds.gymtracker.ui.profile.ProfileScreen
import com.wildodds.gymtracker.ui.library.LibraryScreen
import com.wildodds.gymtracker.ui.library.SessionLibraryScreen
import com.wildodds.gymtracker.ui.create.CreateProgramScreen
import com.wildodds.gymtracker.ui.session.SessionScreen
import com.wildodds.gymtracker.ui.session.SessionViewModel
import com.wildodds.gymtracker.ui.session.SessionInfoScreen
import com.wildodds.gymtracker.ui.settings.SettingsScreen
import com.wildodds.gymtracker.ui.achievements.AchievementsScreen
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import kotlinx.coroutines.launch

data class BottomNavItem(val route: String, val icon: ImageVector, val label: String)

private val homeTab     = BottomNavItem("home",     Icons.Default.Home,                     "Home")
private val libraryTab  = BottomNavItem("library",  Icons.AutoMirrored.Filled.LibraryBooks, "Library")
private val profileTab  = BottomNavItem("profile",  Icons.Default.Person,                   "Profile")

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController      = navController,
        startDestination   = "main",
        modifier           = Modifier.fillMaxSize(),
        enterTransition    = { NavTransitions.enter },
        exitTransition     = { NavTransitions.exit },
        popEnterTransition = { NavTransitions.popEnter },
        popExitTransition  = { NavTransitions.popExit }
    ) {
        // Single composable hosts all tabs — no per-tab back-stack entries,
        // no two-way pager↔nav sync, no freeze on recomposition.
        composable("main") {
            MainPagerScreen(navController = navController)
        }
        composable(
            route     = "session/{sessionId}/{weekNumber}",
            arguments = listOf(
                navArgument("sessionId")  { type = NavType.LongType },
                navArgument("weekNumber") { type = NavType.IntType  }
            )
        ) {
            val vm: SessionViewModel = viewModel()
            SessionScreen(navController = navController, vm = vm)
        }
        composable(
            route     = "session_info/{sessionId}/{weekNumber}",
            arguments = listOf(
                navArgument("sessionId")  { type = NavType.LongType },
                navArgument("weekNumber") { type = NavType.IntType  }
            )
        ) {
            SessionInfoScreen(navController = navController)
        }
        composable("create_program") {
            CreateProgramScreen(navController = navController)
        }
        composable("session_library") {
            SessionLibraryScreen(navController = navController)
        }
        composable("achievements") {
            AchievementsScreen(navController = navController)
        }
        composable("habits") {
            HabitsHost(navController = navController)
        }
        composable("history") {
            HistoryScreen(navController = navController)
        }
        composable("settings") {
            SettingsScreen(navController = navController)
        }
        composable("friends") {
            com.wildodds.gymtracker.ui.friends.FriendsScreen(navController = navController)
        }
        composable(
            route = "friend/{friendId}",
            arguments = listOf(navArgument("friendId") { type = NavType.StringType })
        ) { backStackEntry ->
            val fid = backStackEntry.arguments?.getString("friendId")
            if (fid != null) com.wildodds.gymtracker.ui.friends.FriendDetailScreen(navController, fid)
            else navController.popBackStack()
        }
        composable("account_auth") {
            com.wildodds.gymtracker.ui.auth.AccountAuthFlow(onDone = { navController.popBackStack() })
        }
        composable("delete_account") {
            com.wildodds.gymtracker.ui.account.DeleteAccountScreen(navController = navController)
        }
        composable(
            route = "legal/{docKey}",
            arguments = listOf(navArgument("docKey") { type = NavType.StringType })
        ) { backStackEntry ->
            val doc = com.wildodds.gymtracker.ui.legal.LegalDoc.byKey(backStackEntry.arguments?.getString("docKey"))
            if (doc != null) com.wildodds.gymtracker.ui.legal.LegalDocScreen(navController, doc)
            else navController.popBackStack()
        }
    }
}

@Composable
private fun HabitsHost(navController: androidx.navigation.NavController) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Habits", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { HabitsScreen() }
    }
}

@Composable
private fun MainPagerScreen(
    navController: androidx.navigation.NavController
) {
    val items = remember { listOf(homeTab, libraryTab, profileTab) }

    // A friend-invite link opened the app: offer to add the friend from anywhere.
    val pendingInvite by com.wildodds.gymtracker.ui.friends.PendingFriendInvite.code.collectAsState()
    pendingInvite?.let { code ->
        val friendsVm: com.wildodds.gymtracker.ui.friends.FriendsViewModel = viewModel()
        com.wildodds.gymtracker.ui.friends.RedeemCodeDialog(
            initial = code,
            onRedeem = { c ->
                friendsVm.redeem(c)
                com.wildodds.gymtracker.ui.friends.PendingFriendInvite.code.value = null
                navController.navigate("friends")
            },
            onDismiss = { com.wildodds.gymtracker.ui.friends.PendingFriendInvite.code.value = null }
        )
    }

    val pagerState = rememberPagerState(initialPage = 0) { items.size }
    val scope      = rememberCoroutineScope()

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state    = pagerState,
            // Compose neighbouring tabs ahead of time so the first swipe never stutters on a
            // cold page composition.
            beyondBoundsPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = 80.dp)
        ) { page ->
            when (items[page].route) {
                "home"    -> HomeScreen(navController = navController)
                "library" -> LibraryScreen(navController = navController)
                "profile" -> ProfileScreen(navController = navController)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            AnimatedBottomNav(
                items          = items,
                selectedPage   = pagerState.currentPage,
                onTabSelected  = { idx ->
                    scope.launch { pagerState.animateScrollToPage(idx) }
                }
            )
        }
    }
}

@Composable
private fun AnimatedBottomNav(
    items:          List<BottomNavItem>,
    selectedPage:   Int,
    onTabSelected:  (Int) -> Unit
) {
    val accent = LocalAccentColor.current

    Row {
        items.forEachIndexed { index, item ->
            val isSelected = selectedPage == index
            NavigationBarItem(
                selected  = isSelected,
                onClick   = { onTabSelected(index) },
                icon      = { Icon(item.icon, contentDescription = item.label) },
                label     = { Text(item.label) },
                colors    = NavigationBarItemDefaults.colors(
                    selectedIconColor   = Color.White,
                    indicatorColor      = accent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
