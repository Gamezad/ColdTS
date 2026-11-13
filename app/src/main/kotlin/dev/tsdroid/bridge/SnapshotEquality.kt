package dev.tsdroid.bridge

import dev.tslib.Channel
import dev.tslib.ServerInfo
import dev.tslib.User

// JNI creates new Java objects on every poll. Compare their values so an
// unchanged snapshot does not retrigger every observer and asset download.
internal fun sameChannels(a: List<Channel>, b: List<Channel>): Boolean =
    a.size == b.size && a.indices.all { i ->
        val x = a[i]; val y = b[i]
        x.id == y.id && x.parentId == y.parentId && x.name == y.name &&
            x.topic == y.topic && x.description == y.description && x.order == y.order &&
            x.isPermanent == y.isPermanent && x.isSemiPermanent == y.isSemiPermanent &&
            x.isDefault == y.isDefault && x.hasPassword == y.hasPassword &&
            x.codec == y.codec && x.codecQuality == y.codecQuality &&
            x.maxClients == y.maxClients && x.maxFamilyClients == y.maxFamilyClients &&
            x.neededTalkPower == y.neededTalkPower && x.iconId == y.iconId &&
            x.permissionHints == y.permissionHints
    }

internal fun sameUsers(a: List<User>, b: List<User>): Boolean =
    a.size == b.size && a.indices.all { i ->
        val x = a[i]; val y = b[i]
        x.id == y.id && x.uid == y.uid && x.databaseId == y.databaseId &&
            x.channelId == y.channelId && x.nickname == y.nickname && x.clientType == y.clientType &&
            x.isTalking == y.isTalking && x.isInputMuted == y.isInputMuted &&
            x.isOutputMuted == y.isOutputMuted && x.hasInputHardware == y.hasInputHardware &&
            x.hasOutputHardware == y.hasOutputHardware && x.isAway == y.isAway &&
            x.isRecording == y.isRecording && x.isPrioritySpeaker == y.isPrioritySpeaker &&
            x.isChannelCommander == y.isChannelCommander && x.isTalker == y.isTalker &&
            x.talkPower == y.talkPower && x.awayMessage == y.awayMessage &&
            x.serverGroups.contentEquals(y.serverGroups) && x.channelGroup == y.channelGroup &&
            x.platform == y.platform && x.version == y.version && x.country == y.country &&
            x.description == y.description && x.avatarId == y.avatarId && x.iconId == y.iconId
    }

internal fun sameServerInfo(a: ServerInfo?, b: ServerInfo?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.name == b.name && a.platform == b.platform && a.version == b.version &&
        a.maxClients == b.maxClients && a.clientsOnline == b.clientsOnline &&
        a.channelsOnline == b.channelsOnline && a.uptime == b.uptime &&
        a.welcomeMessage == b.welcomeMessage && a.iconId == b.iconId
}

internal fun Channel.withPermissionHints(hints: Long): Channel = Channel(
    id, parentId, name, topic, description, order, isPermanent, isSemiPermanent,
    isDefault, hasPassword, codec, codecQuality, maxClients, maxFamilyClients,
    neededTalkPower, iconId, hints,
)
