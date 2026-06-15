package com.coursemapper.sharing

import androidx.core.content.FileProvider

/** Own [FileProvider] subclass so the app gets its own authority (`com.coursemapper.fileprovider`). */
class RouteFileProvider : FileProvider()
