# landing

The public page at `https://sentra-relay.site`: two static files, `index.html` and `favicon.svg` (the app's launcher icon), served by Caddy from
`/var/www/sentra` on the VPS. Its vibration demo copies the timings in
[`firmware/SENTRA_firmware`](../firmware/README.md); change both together.

Upload after editing:

```bash
scp landing/index.html landing/favicon.svg vps:/var/www/sentra/
```

Caddy configuration, next to the `relay.sentra-relay.site` block:

```
www.sentra-relay.site {
    redir https://sentra-relay.site{uri}
}

sentra-relay.site {
    root * /var/www/sentra
    file_server
}
```
