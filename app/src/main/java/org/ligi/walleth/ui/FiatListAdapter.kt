package org.ligi.walleth.ui

import android.app.Activity
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import org.ligi.walleth.R
import org.ligi.walleth.data.config.Settings
import org.ligi.walleth.data.exchangerate.ExchangeRateProvider

class FiatListAdapter(exchangeRateProvider: ExchangeRateProvider, val activity: Activity, val settings: Settings) : RecyclerView.Adapter<FiatListItemViewHolder>() {

    val availableFiatInfoList = exchangeRateProvider.getAvailableFiatInfoMap().values.toList()

    override fun getItemCount() = availableFiatInfoList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
            = FiatListItemViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.fiat_list_item, parent, false), activity, settings)


    override fun onBindViewHolder(holder: FiatListItemViewHolder, position: Int) {
        holder.bind(availableFiatInfoList[position])
    }

}