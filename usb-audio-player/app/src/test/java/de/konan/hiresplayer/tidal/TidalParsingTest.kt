package de.konan.hiresplayer.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalParsingTest {

    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1HS-z_jW-SKFuJ0Cs",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun parsesCollectionTracksWithIncludes() {
        val json = """
        {
          "data": [ { "id": "1", "type": "tracks" }, { "id": "2", "type": "tracks" } ],
          "included": [
            { "id": "1", "type": "tracks",
              "attributes": { "title": "Song", "version": "Remaster", "duration": "PT3M25S", "mediaTags": ["LOSSLESS", "HIRES_LOSSLESS"] },
              "relationships": {
                "artists": { "data": [ { "id": "a1", "type": "artists" }, { "id": "a2", "type": "artists" } ] },
                "albums": { "data": [ { "id": "al1", "type": "albums" } ] }
              } },
            { "id": "2", "type": "tracks", "attributes": { "title": "Ohne Relationen" } },
            { "id": "a1", "type": "artists", "attributes": { "name": "Alpha" } },
            { "id": "a2", "type": "artists", "attributes": { "name": "Beta" } },
            { "id": "al1", "type": "albums", "attributes": { "title": "Album" },
              "relationships": { "coverArt": { "data": [ { "id": "c1", "type": "artworks" } ] } } },
            { "id": "c1", "type": "artworks", "attributes": { "files": [
              { "href": "https://img/1280.jpg", "meta": { "width": 1280, "height": 1280 } },
              { "href": "https://img/320.jpg", "meta": { "width": 320, "height": 320 } },
              { "href": "https://img/80.jpg", "meta": { "width": 80, "height": 80 } } ] } }
          ],
          "links": { "self": "/x", "next": "/userCollections/u/relationships/tracks?page[cursor]=abc" }
        }
        """.trimIndent()
        val doc = JsonApiDocument(json)
        val tracks = doc.primary().map(doc::toTrack)

        assertEquals(2, tracks.size)
        val t = tracks[0]
        assertEquals("Song (Remaster)", t.title)
        assertEquals("Alpha, Beta", t.artists)
        assertEquals("Album", t.album)
        assertEquals(205_000L, t.durationMs)
        assertEquals("Hi-Res", t.qualityLabel)
        assertEquals("https://img/320.jpg", t.artworkUrl)

        assertEquals("Ohne Relationen", tracks[1].title)
        assertEquals("", tracks[1].artists)
        assertNull(tracks[1].artworkUrl)

        assertEquals("/userCollections/u/relationships/tracks?page[cursor]=abc", doc.nextLink())
    }

    @Test
    fun parsesSearchResultRelationships() {
        val json = """
        {
          "data": [ { "id": "q", "type": "searchResults",
            "relationships": {
              "tracks": { "data": [ { "id": "1", "type": "tracks" } ] },
              "albums": { "data": [ { "id": "al1", "type": "albums" } ] } } } ],
          "included": [
            { "id": "1", "type": "tracks", "attributes": { "title": "Treffer", "duration": "PT59S" } },
            { "id": "al1", "type": "albums", "attributes": { "title": "Platte", "numberOfItems": 12 } }
          ]
        }
        """.trimIndent()
        val doc = JsonApiDocument(json)
        val result = doc.primary().single()
        assertEquals("Treffer", doc.related(result, "tracks").map(doc::toTrack).single().title)
        val album = doc.related(result, "albums").map(doc::toAlbum).single()
        assertEquals(12, album.numberOfItems)
        assertNull(doc.nextLink())
    }
}
