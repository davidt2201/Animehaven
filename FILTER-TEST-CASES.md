# Content filter test cases

These cases test exact site metadata labels, not title-name keywords.

| Site metadata label | Expected |
|---|---|
| Hentai | Block |
| NSFW | Block |
| Explicit | Block |
| Erotica | Block |
| Pornographic | Block |
| 18+ | Block |
| R-18+ | Block |
| Adult Only | Block |
| Ecchi | Do not block on this label alone |
| Mature | Do not block on this label alone |
| Adult Cast | Do not block on this label alone |
| Age Transformation | Do not block on this label alone |
| School Life | Do not block on this label alone |

A title with no readable site tag or rating metadata is hidden because its classification cannot be checked. No settings or search option can override this filter.
