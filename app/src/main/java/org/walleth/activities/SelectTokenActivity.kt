package org.walleth.activities

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.MenuItem
import com.github.salomonbrys.kodein.LazyKodein
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import kotlinx.android.synthetic.main.activity_list.*
import org.ligi.kaxt.startActivityFromClass
import org.walleth.R
import org.walleth.data.config.Settings
import org.walleth.data.exchangerate.TokenProvider
import org.walleth.ui.TokenListAdapter

class SelectTokenActivity : AppCompatActivity()  {

    val tokenProvider: TokenProvider by LazyKodein(appKodein).instance()
    val settings: Settings by LazyKodein(appKodein).instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_list)

        supportActionBar?.subtitle = "Select token"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recycler_view.layoutManager = LinearLayoutManager(this)

        fab.setOnClickListener {
            startActivityFromClass(CreateTokenDefinitionActivity::class)
        }
    }

    override fun onResume() {
        super.onResume()

        recycler_view.adapter = TokenListAdapter(tokenProvider , this)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

}

