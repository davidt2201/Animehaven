# AnimeHeaven for Aniyomi / Anikku

An English-language source module for AnimeHeaven.

## Current status

The source module is being compiled against the current Yuzono extension framework by GitHub Actions. A green build confirms compilation, not successful runtime playback. The live site can change markup and video-handling behaviour, so catalogue, search, episode loading, and playback still require device testing.

## Content filtering

Titles are checked against site-provided tag and rating metadata. Exact explicit classifications are blocked. The filter does not scan title names, aliases, descriptions, or broad labels such as Ecchi, Mature, Adult Cast, Age Transformation, or School Life.

When a title has no readable classification metadata, the source hides it rather than guessing. The same check is repeated before details, episodes, and video-server resolution. There is no override setting or search bypass for blocked titles. Related-title recommendations are disabled because the source cannot verify them in bulk.

## Build

Open the **Actions** tab to view the compile job and download its debug APK artifact after a successful run.

This project should not be described as release-ready until it has passed CI and been tested in Aniyomi/Anikku against the current AnimeHeaven site.
