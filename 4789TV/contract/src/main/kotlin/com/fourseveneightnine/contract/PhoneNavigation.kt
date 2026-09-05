package com.fourseveneightnine.contract

enum class PhoneDoor {
    Discover,
    Wall,
}

sealed interface PhoneRoute {
    data class Door(val door: PhoneDoor) : PhoneRoute
    data class Detail(val titleID: String) : PhoneRoute
    data class Sources(val titleID: String) : PhoneRoute
    data class Player(val titleID: String) : PhoneRoute
}

object PhoneNavigationPolicy {
    val topLevelDoors: Set<PhoneDoor> = PhoneDoor.entries.toSet()

    fun switchDoor(current: PhoneRoute, target: PhoneDoor): PhoneRoute.Door =
        if (current is PhoneRoute.Door && current.door == target) current else PhoneRoute.Door(target)

    fun backStackAfterDoorSwitch(target: PhoneDoor): List<PhoneRoute> = listOf(PhoneRoute.Door(target))
}
