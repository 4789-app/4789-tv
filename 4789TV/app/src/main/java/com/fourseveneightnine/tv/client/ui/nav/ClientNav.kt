package com.fourseveneightnine.tv.client.ui.nav

/**
 * What a screen may ask the shell to do. Screens never touch the NavController; the shell owns
 * the back stack rule (one entry behind Home) and the rail.
 */
internal interface ClientNav {
    fun openRail()
    fun back()
    fun openHome()
    fun openLiveTv()
    fun openLiveItem(kind: String, id: String)
    fun openMultiview(channelId: String)
    fun openDiscover()
    fun openAllCatalogs()
    fun openDetail(type: String, id: String)
    fun openStreams(type: String, id: String, season: Int? = null, episode: Int? = null)
    fun openCollection(id: String)
    fun openCollectionEditor(id: String?)
    fun openSettings(page: String = Route.Settings.DEFAULT_PAGE)
    fun openPlayer()
    fun toast(message: String)
}
