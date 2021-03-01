package org.walleth.notifications


const val NOTIFICATION_ID_DATA_SERVICE = 1247
const val NOTIFICATION_ID_TRANSACTION_NOTIFICATIONS = NOTIFICATION_ID_DATA_SERVICE + 1
const val NOTIFICATION_ID_GETH = NOTIFICATION_ID_TRANSACTION_NOTIFICATIONS +1
const val NOTIFICATION_ID_WALLETCONNECT = NOTIFICATION_ID_GETH +1

const val NOTIFICATION_CHANNEL_ID_DATA_SERVICE = "dataservice"
const val NOTIFICATION_CHANNEL_ID_TRANSACTION_NOTIFICATIONS = "txnotify"
const val NOTIFICATION_CHANNEL_ID_GETH = "geth"
const val NOTIFICATION_CHANNEL_ID_WALLETCONNECT = "walletconnect"