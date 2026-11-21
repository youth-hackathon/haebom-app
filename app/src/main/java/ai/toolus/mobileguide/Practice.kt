package ai.toolus.mobileguide

/**
 * The things to learn that the home screen offers ("배울 것 보기").
 *
 * Each one is a request sentence: starting it sends the sentence to the guide server exactly as a typed request
 * would. The sentences are the ones the manual server (haebom-manual) keeps manuals for, so a manual server answers
 * them from its manual and any other guide server plans them step by step. The app knows nothing else about them.
 */
object Practice {
    class Item(val id: String, val title: String, val short: String, val summary: String, val task: String)

    val items = listOf(
        Item("alarm-8", "아침 8시 알람 맞추기", "아침 8시 알람", "시계 앱에서 오전 8시 알람을 맞춰 봅니다.", "오전 8시에 알람 설정하기"),
        Item("font-size", "글자 크게 하기", "글자 크게 하기", "설정에서 화면의 글자를 더 크게 바꿔 봅니다.", "글자 크기 키우기"),
        Item("bluetooth", "블루투스 화면 열기", "블루투스 화면", "설정에서 블루투스를 켜고 끄는 화면을 찾아갑니다.", "설정에서 블루투스 화면 열어줘"),
    )

    /** What the home screen's big block offers a person who has not started anything yet. */
    val first: Item = items.first { it.id == "font-size" }

    private fun of(task: String) = items.firstOrNull { it.task == task }

    /** What a task's "done once" mark is kept under: the item's id, or the sentence itself for a typed request. */
    fun key(task: String): String = of(task)?.id ?: task

    /** One line for the home screen's "이어서 하기" block. */
    fun short(task: String): String = of(task)?.short ?: task
}
