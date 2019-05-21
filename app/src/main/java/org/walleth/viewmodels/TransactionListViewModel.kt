package org.walleth.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import org.ligi.kaxt.livedata.CombinatorMediatorLiveData
import org.walleth.data.AppDatabase
import org.walleth.data.networks.ChainInfoProvider
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.transactions.TransactionEntity
import org.walleth.kethereum.model.AddressOnChain

class TransactionListViewModel(app: Application,
                               appDatabase: AppDatabase,
                               currentAddressProvider: CurrentAddressProvider,
                               chainInfoProvider: ChainInfoProvider) : AndroidViewModel(app) {


    val isOnboardingVisible = MutableLiveData<Boolean>().apply { value = false }

    var hasIncoming = MutableLiveData<Boolean>().apply { value = false }
    var hasOutgoing = MutableLiveData<Boolean>().apply { value = false }

    val isEmptyViewVisible = CombinatorMediatorLiveData(listOf(isOnboardingVisible, hasIncoming, hasOutgoing)) {
        (isOnboardingVisible.value == false) && (hasIncoming.value == false) && (hasOutgoing.value == false)
    }

    private val addressOnChainMediator = CombinatorMediatorLiveData(listOf(currentAddressProvider, chainInfoProvider)) {
        AddressOnChain(currentAddressProvider.getCurrentNeverNull(), chainInfoProvider.getCurrentChainId())
    }

    val incomingLiveData: LiveData<PagedList<TransactionEntity>> = Transformations.switchMap(addressOnChainMediator) { addressOnChain ->
        val incomingDataSource = appDatabase.transactions.getIncomingPaged(addressOnChain.address, addressOnChain.chain.value)
        LivePagedListBuilder<Int, TransactionEntity>(incomingDataSource, 50).build()
    }

    val outgoingLiveData: LiveData<PagedList<TransactionEntity>> = Transformations.switchMap(addressOnChainMediator) { addressOnChain ->
        val outgoingDataSourceDataSource = appDatabase.transactions.getOutgoingPaged(addressOnChain.address, addressOnChain.chain.value)
        LivePagedListBuilder<Int, TransactionEntity>(outgoingDataSourceDataSource, 50).build()
    }

}