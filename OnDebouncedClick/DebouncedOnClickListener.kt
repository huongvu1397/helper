
import android.view.View
import java.lang.System.currentTimeMillis

private const val DEFAULT_DEBOUNCE_INTERVAL = 750L

abstract class DebouncedOnClickListener(
  private val delayBetweenClicks: Long = DEFAULT_DEBOUNCE_INTERVAL
) : View.OnClickListener {
  private var lastClickTimestamp = -1L

  @Deprecated(
      message = "onDebouncedClick should be overridden instead.",
      replaceWith = ReplaceWith("onDebouncedClick(v)")
  )

  override fun onClick(v: View) {
    val now = currentTimeMillis()
    if (lastClickTimestamp == -1L || now >= (lastClickTimestamp + delayBetweenClicks)) {
      onDebouncedClick(v)
    }
    lastClickTimestamp = now
  }

  abstract fun onDebouncedClick(v: View)
}

/**
 * Sets a click listener that prevents quick repeated clicks.
 *
 */
fun View.onDebouncedClick(
  delayBetweenClicks: Long = DEFAULT_DEBOUNCE_INTERVAL,
  click: (view: View) -> Unit
) {
  setOnClickListener(object : DebouncedOnClickListener(delayBetweenClicks) {
    override fun onDebouncedClick(v: View) = click(v)
  })
}



