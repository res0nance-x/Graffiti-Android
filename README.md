### What is Graffiti?

**Graffiti** is a secure, serverless, peer-to-peer (P2P) messaging application designed for absolute privacy and digital sovereignty. Unlike typical chat apps (such as WhatsApp, Telegram, or Discord) that route messages through central company servers, Graffiti connects you directly to other people.

Every text message and file shared on Graffiti is encrypted end-to-end (E2EE) on your device before it ever leaves. It is stored on your local disk, keeping you in complete control of your data.

------

### Key Features from a User's Perspective

- **True Peer-to-Peer Connections:** You can stat a local server port with one click, allowing nearby users to discover your node automatically or connect to you directly using your IP address and port.
- **End-to-End Encryption by Default:** Graffiti uses cryptographic keys (public/private key pairs) for identities. Only the specific recipient you select can decrypt and read your messages.
- **Deterministic Identities:** You can generate and recover your cryptographic profiles using a simple seed phrase. You can manage multiple identities simultaneously (e.g., separate profiles for different topics, forums, or personas).
- **Store-and-Forward Relays:** If you want to communicate with someone who isn't online at the same time as you, you can connect to a shared **Relay**. The relay securely caches the encrypted message (which the relay cannot read) and delivers it to your peer when they log on and sync.
- **Local Discovery:** Graffiti automatically scans your local network using multicast discovery, making it easy to find and chat with other users on the same Wi-Fi network without configuring anything.
- **Granular Storage Control:** Because your messages are stored locally on your device, Graffiti gives you full transparency over your storage. You can set strict storage quotas (in MB), see exactly how much space is being used, and purge cached storage instantly.

------

### Why You Would Want to Use Graffiti

1. **Absolute Privacy & Censorship Resistance** Since there is no central database or corporation running Graffiti, there is no one tracking your metadata, harvesting your contacts, or analyzing your communication patterns. There are no servers to be shut down or blocked by external entities.
2. **Offline & Local Mesh Communication** If the internet goes down, you can still communicate. Because Graffiti can discover and connect to peers over local Wi-Fi or LAN networks automatically, it is perfect for local mesh communication in emergencies, remote areas, or offline events.
3. **Total Control of Your Digital Footprint** With Graffiti, you decide exactly how long messages are stored on your device and how much storage space they can take. You can export or delete your identities and peer keys at any time, leaving no digital trace on the web.
4. **Asynchronous P2P Messaging** Pure P2P networks usually require both users to be online at the same time to chat. Graffiti solves this with secure, zero-knowledge Relays—allowing you to drop off encrypted notes for friends that they can pick up when they connect, without compromising privacy.

Graffiti is written in Kotlin and Java and is currently tested on Windows and Android. It can theoretically run on any JVM capable OS and on Android.

### Download:

- [Windows](https://www.res0nance.cc/graffiti/graffiti.zip)
- [Android](https://www.res0nance.cc/graffiti/graffiti.apk)

### Documentation

[How to use Graffiti](https://www.res0nance.cc/graffiti/graffiti-doc.html)
