---
paths:
  - "app/src/**/*.kt"
---

# Performance Rules

- Responsive UI over decoration. No animations by default.
- No background work, polling, or services unless the feature spec says approved.
- Lazy lists, paging, and thumbnails for media. Release resources (players, cameras) when off-screen.
- Don't assume 16 GB RAM; design for low-memory phones too.
