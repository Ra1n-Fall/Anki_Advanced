package com.example.anki_advanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController


//HomeActivity.kt
//│
//│  HomeScreen을 띄워주기만
//│   setContent { HomeScreen() }
//HomeScreen.kt
//│
//│  화면 그리기만
//│  데이터는 ViewModel
//│
//│  val viewModel = HomeViewModel()     ← ViewModel 연결
//│  val decks = viewModel.decks         ← 데이터 가져오기
//│
//│  DeckCard(deck)                      ← 화면에 그리기
//│  DeckCard(deck)
//│  DeckCard(deck)
//HomeViewModel.kt
//│
//│  DB 조회하고 데이터 보관
//│
//│  db.deckDao().getAll()    ← DB 조회
//│  val decks = [...]        ← 결과 보관

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [변경] setContentView(R.layout.activity_home) → setContent { }
        // 기존: XML 파일 하나를 화면으로 지정
        //   setContentView(R.layout.activity_home)
        //   → activity_home.xml을 파싱해서 View 객체 트리를 만든 뒤 화면에 붙임
        //
        // 변경: setContent { } 블록 안의 Compose 코드가 곧 화면
        //   XML 파일 없이 코드만으로 화면을 구성
        //   블록 안에 쓴 Compose 함수들이 호출되면서 화면이 그려짐
        setContent {

            // navController = 화면 간 이동을 담당하는 객체
            // 화면 이동 처리를 하나의 객체에서 모두 호출

            // rememberNavController() :
            //   Compose는 상태가 바뀔 때마다 화면을 다시 그림(recomposition)
            //   그때마다 navController를 새로 만들면 이동 기록이 사라짐
            //   remember = "이미 만든 객체를 기억해뒀다가 재사용"
            val navController = rememberNavController()

            // NavHost = 화면들을 등록해두는 컨테이너

            //   NavHost 안에 composable()로 화면들을 등록
            //   화면(Screen)을 경로(route)로 관리
            //

            NavHost(
                navController = navController, //연결할 컨트롤러
                startDestination = "home"// 앱 첫 실행 시 보여줄 화면의 경로
            ) {
                // composable("경로") { 보여줄 화면 }

                // navController를 HomeScreen에 넘기는 이유:
                //   HomeScreen 안에서 Study나 DeckManage로 이동할 때
                //   navController.navigate("study/...") 를 호출해야 하기 때문
                composable("home") {
                    HomeScreen(navController = navController)
                }


                //   navController.navigate("study/${deck.id}/${deck.name}")
                //   다른 화면으로 이동할 때 경로 자체에 이동할 화면에 넘길 값을 포함해서 이동
                composable("study/{deckId}/{deckName}") { backStackEntry ->

                    // backStackEntry = 현재 화면의 경로 정보를 담은 객체
                    // arguments = 경로에서 {} 로 선언한 변수들의 값
                    //

                    val deckId = backStackEntry.arguments// 경로에서 문자열을 받아와서 다시 long 형태로 변환
                        ?.getString("deckId")
                        ?.toLong()
                        ?: return@composable

                    val deckName = backStackEntry.arguments
                        ?.getString("deckName")
                        ?: ""

                    // NavHost는 꺼낸 값을 StudyScreen에 직접 파라미터로 전달
                    StudyScreen(
                        navController = navController,
                        deckId = deckId,
                        deckName = deckName
                    )
                }

                // DeckManageScreen도 StudyScreen과 동일한 패턴
                composable("deckManage/{deckId}/{deckName}") { backStackEntry ->
                    val deckId = backStackEntry.arguments
                        ?.getString("deckId")
                        ?.toLong()
                        ?: return@composable

                    val deckName = backStackEntry.arguments
                        ?.getString("deckName")
                        ?: ""

                    DeckManageScreen(
                        navController = navController,
                        deckId = deckId,
                        deckName = deckName
                    )
                }
            }
        }
    }
}