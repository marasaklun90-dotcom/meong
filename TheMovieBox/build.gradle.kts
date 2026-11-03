android {
    namespace = "com.themoviebox"
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

version = 1

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/TheAlyss/cloudstream-AlyssRepo")
    description = "TheMovieBox - Movies and TV Series"
    authors = listOf("TheAlyss")
    language = "id"
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
    )
    iconUrl = "https://movieboxph.app/wp-content/uploads/2025/11/Movie-Box-icon.webp"
}
