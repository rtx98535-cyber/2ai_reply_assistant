package com.animus.aireplyassistant.generation

interface ReplySuggestionsApi {
    suspend fun fetchSuggestions(req: ReplySuggestionsRequest): ReplySuggestionsResponse
}

