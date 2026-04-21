package com.example.a404

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.google.android.material.card.MaterialCardView

/**
 * Browse screen – Supabase Edition (V23)
 *
 * Replaces GitHub repo browsing with a Supabase query via SupabaseRepository.
 * Lists all PDFs that match the user's selected course + year.
 * Search, hamburger menu, and bottom nav are unchanged.
 *
 * Fix (V23): Course + year are now captured once at onCreate time into
 * instance fields (browseCourse / browseYear).  onResume detects when the
 * user has removed their main book from the Library screen (which wipes
 * SELECTED_COURSE / SELECTED_YEAR from SharedPreferences) and redirects to
 * ChoosingPhaseActivity instead of falling back to "show all books".
 * This prevents the Browse screen from displaying books from unrelated
 * courses after an unsave action in the Library.
 */
class BrowseActivity : ComponentActivity() {

    private val allBookItems   = mutableListOf<Pair<MaterialCardView, String>>()
    private var hamburgerPopup: PopupWindow? = null

    // Snapshot of course + year taken at onCreate — Browse always filters by
    // these values and never re-reads them from prefs mid-session.
    private var browseCourse: String = ""
    private var browseYear:   String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.browse_sources_1)

        // Capture the user's course/year once at startup so that a later
        // unsave in Library (which clears the prefs) cannot corrupt the filter.
        val prefs    = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        browseCourse = prefs.getString("SELECTED_COURSE", "") ?: ""
        browseYear   = prefs.getString("SELECTED_YEAR",   "") ?: ""

        // ── Hamburger menu ────────────────────────────────────────────────────
        findViewById<ImageView>(R.id.menu_button).setOnClickListener { anchor ->
            if (hamburgerPopup?.isShowing == true) hamburgerPopup?.dismiss()
            else hamburgerPopup = showHamburgerMenu(anchor)
        }

        // ── Search bar ────────────────────────────────────────────────────────
        val searchIcon      = findViewById<View>(R.id.search_icon)
        val searchContainer = findViewById<View>(R.id.search_bar_container)
        val searchInput     = findViewById<EditText>(R.id.search_input)
        val cancelBtn       = findViewById<TextView>(R.id.btn_cancel_search)
        val noResults       = findViewById<View>(R.id.no_results_view)

        searchIcon.setOnClickListener {
            searchContainer.visibility = View.VISIBLE
            searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
        cancelBtn.setOnClickListener {
            searchInput.setText("")
            searchContainer.visibility = View.GONE
            noResults.visibility       = View.GONE
            showAllBooks()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filterBooks(s?.toString() ?: "", noResults) }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else false
        }

        loadBooksFromSupabase()
        setupNavigation()
    }

    override fun onResume() {
        super.onResume()
        // Guard: if the user removed their main book from LibraryActivity the
        // prefs for SELECTED_COURSE/YEAR will have been cleared.  Re-check now
        // so we never show an unfiltered list on return from Library.
        val prefs          = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val currentCourse  = prefs.getString("SELECTED_COURSE", "") ?: ""
        val currentYear    = prefs.getString("SELECTED_YEAR",   "") ?: ""

        when {
            // Case 1 – prefs were cleared while we were away (unsave of main
            //          book).  The snapshot is still valid; restore the prefs
            //          so the rest of the app stays consistent, then reload.
            browseCourse.isNotBlank() && browseYear.isNotBlank() &&
            (currentCourse.isBlank() || currentYear.isBlank()) -> {
                prefs.edit()
                    .putString("SELECTED_COURSE", browseCourse)
                    .putString("SELECTED_YEAR",   browseYear)
                    .apply()
                // No visual reload needed — the list already shows the right
                // books from onCreate.  Nothing to do here.
            }

            // Case 2 – a completely fresh launch where no course was ever set
            //          (shouldn't normally reach Browse, but be safe).
            browseCourse.isBlank() || browseYear.isBlank() -> {
                // Nothing extra to do; onCreate already called loadBooksFromSupabase
                // which handles the blank-course path.
            }

            // Case 3 – normal resume, course/year unchanged.  No reload needed.
        }
    }

    override fun onPause() {
        super.onPause()
        hamburgerPopup?.dismiss()
    }

    // ── Load from Supabase ────────────────────────────────────────────────────

    private fun loadBooksFromSupabase() {
        val loading   = findViewById<ProgressBar>(R.id.books_loading)
        val container = findViewById<LinearLayout>(R.id.books_list_container)
        val emptyView = findViewById<View>(R.id.empty_view)

        loading.visibility   = View.VISIBLE
        emptyView.visibility = View.GONE
        container.removeAllViews()
        allBookItems.clear()

        // Always use the snapshot taken at onCreate — never re-read prefs here.
        // This prevents a mid-session unsave in Library from changing the filter.
        if (browseCourse.isBlank() || browseYear.isBlank()) {
            // User hasn't selected a course yet — show all books
            SupabaseRepository.fetchAllBooks(
                onSuccess = { books ->
                    runOnUiThread {
                        loading.visibility = View.GONE
                        if (books.isEmpty()) {
                            emptyView.visibility = View.VISIBLE
                        } else {
                            books.forEach { book -> addBookCard(container, book) }
                        }
                    }
                },
                onError = { _ ->
                    runOnUiThread {
                        loading.visibility   = View.GONE
                        emptyView.visibility = View.VISIBLE
                    }
                }
            )
        } else {
            // Show only PDFs for the user's specific course + year
            SupabaseRepository.fetchBooksForSlot(
                course    = browseCourse,
                year      = browseYear,
                onSuccess = { books ->
                    runOnUiThread {
                        loading.visibility = View.GONE
                        if (books.isEmpty()) {
                            emptyView.visibility = View.VISIBLE
                        } else {
                            books.forEach { book -> addBookCard(container, book) }
                        }
                    }
                },
                onError = { _ ->
                    runOnUiThread {
                        loading.visibility   = View.GONE
                        emptyView.visibility = View.VISIBLE
                    }
                }
            )
        }
    }

    // ── Book card builder ─────────────────────────────────────────────────────

    private fun addBookCard(container: LinearLayout, book: SupabaseBook) {
        val displayName = book.title
        val subLabel    = if (book.course.isNotBlank() && book.year.isNotBlank())
            "${book.course} · ${book.year}"
        else
            "Supabase Library"

        val dp = resources.displayMetrics.density

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
            radius        = 12 * dp
            cardElevation = 1 * dp
            isClickable   = true
            isFocusable   = true
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
        }

        val iconBg = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).also {
                it.marginEnd = (14 * dp).toInt()
            }
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFEDE7F6.toInt())
        }
        iconBg.addView(TextView(this).apply {
            text = "PDF"
            setTextColor(0xFF6A1B9A.toInt())
            setTypeface(null, Typeface.BOLD)
            textSize = 10f
            gravity  = Gravity.CENTER
        })

        val textCol = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = displayName
            setTextColor(0xFF1A1A1A.toInt())
            textSize  = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines  = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = subLabel
            setTextColor(0xFF888888.toInt())
            textSize     = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (2 * dp).toInt() }
        })

        val viewBtn = Button(this).apply {
            text = "VIEW"
            setTextColor(0xFF6A1B9A.toInt())
            textSize   = 12f
            background = getDrawable(R.drawable.bg_rounded_browse_extension_1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * dp).toInt()
            ).also { it.marginStart = (8 * dp).toInt() }
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            isAllCaps = true
            setOnClickListener { openBookDetail(book, displayName) }
        }

        row.addView(iconBg)
        row.addView(textCol)
        row.addView(viewBtn)
        card.addView(row)
        card.setOnClickListener { openBookDetail(book, displayName) }

        container.addView(card)
        allBookItems.add(Pair(card, "$displayName $subLabel ${book.author}".lowercase()))
    }

    private fun openBookDetail(book: SupabaseBook, displayName: String) {
        startActivity(Intent(this, BookDetailActivity::class.java).apply {
            putExtra("BOOK_TITLE",       displayName)
            putExtra("BOOK_AUTHOR",      book.author)
            putExtra("BOOK_DESCRIPTION", book.description)
            putExtra("BOOK_COURSE",      book.course)
            putExtra("BOOK_YEAR",        book.year)
            putExtra("BOOK_URL",         book.downloadUrl)
            putExtra("SOURCE",           BookDetailActivity.SOURCE_BROWSE)
        })
    }

    // ── Search helpers ────────────────────────────────────────────────────────

    private fun showAllBooks() {
        allBookItems.forEach { (card, _) -> card.visibility = View.VISIBLE }
    }

    private fun filterBooks(query: String, noResults: View) {
        val q = query.trim().lowercase()
        var anyVisible = false
        allBookItems.forEach { (card, text) ->
            val match = q.isEmpty() || text.contains(q)
            card.visibility = if (match) View.VISIBLE else View.GONE
            if (match) anyVisible = true
        }
        noResults.visibility = if (!anyVisible && q.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ── Hamburger popup ───────────────────────────────────────────────────────

    private fun showHamburgerMenu(anchor: View): PopupWindow {
        val popupView = LayoutInflater.from(this).inflate(R.layout.hamburger_dropdown_menu, null)
        val dp = resources.displayMetrics.density
        val popup = PopupWindow(
            popupView, (200 * dp).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation          = 12 * dp
            animationStyle     = R.style.HamburgerDropdownAnimation
            isOutsideTouchable = true
        }
        popupView.findViewById<View>(R.id.menu_item_guide).setOnClickListener {
            popup.dismiss(); startActivity(Intent(this, GuideActivity::class.java))
        }
        popupView.findViewById<View>(R.id.menu_item_about_us).setOnClickListener {
            popup.dismiss(); startActivity(Intent(this, AboutUsActivity::class.java))
        }
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0], loc[1] + anchor.height + (4 * dp).toInt())
        return popup
    }

    // ── Bottom navigation ─────────────────────────────────────────────────────

    private fun setupNavigation() {
        // nav_browse intentionally omitted — already here
        findViewById<View>(R.id.nav_library)?.setOnClickListener  { startActivity(Intent(this, LibraryActivity::class.java)) }
        findViewById<View>(R.id.nav_history)?.setOnClickListener  { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<View>(R.id.nav_download)?.setOnClickListener { startActivity(Intent(this, DownloadActivity::class.java)) }
    }
}
