package com.familycare.navigation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.familycare.ui.PlaceSearch
import org.junit.Rule
import org.junit.Test

class SearchControlsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun searchButtonRemainsBesideInputAndImeSearchSubmits(){
        compose.setContent{MaterialTheme{Box(Modifier.width(360.dp).height(330.dp)){PlaceSearch{}}}}
        compose.onNodeWithTag("place-search-submit").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("place-search-query").performTextInput("양재")
        compose.onNodeWithTag("place-search-submit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("place-search-query").performImeAction()
        compose.waitUntil(10000){compose.onAllNodesWithText("먼저 차량 연결을 시작해 주세요").fetchSemanticsNodes().isNotEmpty()}
    }
}
