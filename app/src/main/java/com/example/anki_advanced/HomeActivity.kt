package com.example.anki_advanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.anki_advanced.completion.CompletionStudyScreen
import com.example.anki_advanced.gemini.DeckGenerationScreen

// 이 앱의 유일한 진입점(런처) Activity.
// 화면 자체를 그리지는 않고, "어떤 화면(Screen)을 어떤 경로(route)로 오갈 수 있는지"만 정의한다.
//
// 전체 구조 한눈에 보기:
//   HomeActivity.kt   → setContent { NavHost(...) } 로 내비게이션 뼈대만 세팅
//   HomeScreen.kt      → 실제 화면 그리기 (버튼, 리스트 등). 데이터는 안 들고 ViewModel에서 받아옴
//   HomeViewModel.kt   → DB 조회 + 상태(StateFlow) 보관. 화면은 이 값을 구독만 함
//
// [문법] class HomeActivity : ComponentActivity()
//   Jetpack Compose 화면을 쓰는 Activity의 기본 부모 클래스.
//   전통적인 AppCompatActivity + XML 대신, Compose 코드를 바로 그릴 수 있게 해준다.
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [문법] setContent { ... }
        //   XML 레이아웃 파일 없이, 이 블록 안에 쓴 Compose 함수 호출들이 곧 화면이 된다.
        //   (예전 방식은 setContentView(R.layout.activity_home) 처럼 XML을 지정했었음)
        setContent {

            // [문법] rememberNavController()
            //   Compose는 상태가 바뀔 때마다 화면 일부를 다시 그리는데(recomposition),
            //   그때마다 navController를 새로 만들면 지금까지의 화면 이동 기록이 날아가 버린다.
            //   remember{}로 감싸두면 "다시 그려져도 이 객체는 새로 안 만들고 재사용"하게 된다.
            val navController = rememberNavController()

            // [문법] NavHost(navController = ..., startDestination = "home") { composable(...) {...} ... }
            //   화면들을 "경로(route) 문자열"로 등록해두는 컨테이너.
            //   navController.navigate("경로")를 호출하면 여기 등록된 화면으로 전환된다.
            //   startDestination은 앱을 처음 켰을 때 보여줄 화면의 경로.
            NavHost(
                navController = navController,
                startDestination = "home"
            ) {
                // composable("home") { HomeScreen(...) }
                //   "home" 경로로 이동하면 HomeScreen을 그리라는 등록.
                //   navController를 HomeScreen에 넘겨주는 이유: HomeScreen 내부에서
                //   다른 화면(학습, 덱 관리 등)으로 이동할 때 navController.navigate(...)를
                //   직접 호출해야 하기 때문.
                composable("home") {
                    HomeScreen(navController = navController)
                }

                // [문법] "study/{deckId}/{deckName}" 처럼 중괄호로 감싼 부분은 "경로 변수".
                //   navController.navigate("study/3/토익단어") 처럼 실제 값을 넣어 이동하면,
                //   이 화면 쪽에서 backStackEntry.arguments로 그 값들을 꺼낼 수 있다.
                composable("study/{deckId}/{deckName}") { backStackEntry ->

                    // [문법] ?.getString("deckId")?.toLong() ?: return@composable
                    //   ?. 체인: arguments가 null이면 전체가 null, 있으면 "deckId" 값을 문자열로 꺼내고,
                    //   그걸 다시 Long으로 변환. 그 과정 중 어디서든 null이 나오면(값이 없거나
                    //   숫자로 변환 안 되면) ?: 뒤의 코드(return@composable, "이 화면 그리기를 그냥 종료")가 실행됨.
                    val deckId = backStackEntry.arguments
                        ?.getString("deckId")
                        ?.toLong()
                        ?: return@composable

                    val deckName = backStackEntry.arguments
                        ?.getString("deckName")
                        ?: ""

                    // 꺼낸 값을 StudyScreen에 파라미터로 그대로 전달.
                    StudyScreen(
                        navController = navController,
                        deckId = deckId,
                        deckName = deckName
                    )
                }

                // 완주 모드 학습 화면. 진입 경로: navController.navigate("completionStudy/${deck.id}")
                composable("completionStudy/{deckId}") { backStackEntry ->
                    val deckId = backStackEntry.arguments
                        ?.getString("deckId")
                        ?.toLong()
                        ?: return@composable

                    CompletionStudyScreen(
                        navController = navController,
                        deckId = deckId
                    )
                }

                // 덱 관리 화면. study 화면과 완전히 같은 패턴.
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

                // Gemini API로 주제만 입력하면 카드를 자동 생성해주는 화면.
                // 진입 경로: navController.navigate("generateDeck")
                composable("generateDeck") {
                    DeckGenerationScreen(navController = navController)
                }
            }
        }
    }
}
