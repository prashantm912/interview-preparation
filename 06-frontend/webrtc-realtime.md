# WebRTC & Real-Time Media

[← Back to master index](../README.md)

WebRTC (Web Real-Time Communication) is a browser- and native-app-native stack for sub-second, peer-to-peer audio, video, and arbitrary data transfer — no plugins, encrypted by default. This guide walks the full mental model: capture (`getUserMedia`), session negotiation (SDP offer/answer over an out-of-band signaling channel), NAT traversal (ICE/STUN/TURN), media transport (DTLS-SRTP), and how real production systems scale group calls with SFUs, simulcast, and adaptive bitrate. Questions progress from "what is a peer connection" to "how would you architect a 10,000-participant call."

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is WebRTC and what problem does it solve?

WebRTC is a set of standardized APIs and protocols (W3C for the JavaScript APIs, IETF for the wire protocols) that let two endpoints exchange audio, video, and data **directly**, peer-to-peer, with end-to-end encryption and low latency — typically 100–300 ms glass-to-glass versus several seconds for HLS/DASH streaming.

Before WebRTC, real-time browser media required plugins (Flash, Java applets) or proprietary native apps. WebRTC ships natively in every modern browser and is available as a C++/Rust library (`libwebrtc`) for mobile and server use.

Three core APIs:

```
getUserMedia()      → capture camera/mic into a MediaStream
RTCPeerConnection   → negotiate + transport encrypted media between peers
RTCDataChannel      → send arbitrary bytes (chat, files, game state) peer-to-peer
```

Key properties: mandatory encryption (you cannot send unencrypted media), built-in NAT traversal, congestion control, and adaptive bitrate.

### Q2. [Theory] WebRTC is "peer-to-peer." What does that mean, and what is signaling?

Peer-to-peer means the **media** flows directly between the two clients once the connection is established — it does not transit your server (in the simple 1:1 case). This minimizes latency and offloads bandwidth from your infrastructure.

However, two peers cannot find each other on their own. They need to exchange connection metadata (codecs, encryption fingerprints, candidate network addresses) *before* the peer connection exists. That exchange is **signaling**, and crucially **WebRTC does not specify how signaling happens** — it is "out of band." You build it yourself with whatever channel you like: WebSocket, HTTP long-poll, SIP, even copy-pasting a string.

```
   Peer A                  Your signaling server                Peer B
     |  --- offer (SDP) -------> | --- offer ------------------->  |
     |  <-------- answer (SDP) - | <------- answer (SDP) --------  |
     |  --- ICE candidates ----> | --- ICE candidates ---------->  |
     |  <----- ICE candidates -- | <----- ICE candidates -------   |
     |                                                             |
     |  ===========  direct encrypted media (P2P)  =============   |
```

Once signaling completes and ICE connects, the server is out of the media path.

### Q3. [Practical] Capture the user's camera and microphone with getUserMedia.

`navigator.mediaDevices.getUserMedia(constraints)` returns a `Promise<MediaStream>`. It prompts the user for permission and resolves with a stream containing audio and/or video tracks.

```javascript
async function startCamera() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } }
    });
    document.querySelector('#localVideo').srcObject = stream;
    return stream;
  } catch (err) {
    // NotAllowedError (denied), NotFoundError (no device), NotReadableError (in use)
    console.error('getUserMedia failed:', err.name, err.message);
    throw err;
  }
}
```

Constraints can be exact (`{ exact: ... }`, fails if unmet) or ideal (best-effort). `getUserMedia` requires a **secure context** (HTTPS or `localhost`) — it silently rejects on plain HTTP.

### Q4. [Theory] What is a MediaStream and a MediaStreamTrack?

A `MediaStream` is a container — a logical group of one or more `MediaStreamTrack` objects. A track represents a single media source: one camera, one microphone, or one screen capture. Tracks have a `kind` (`"audio"` or `"video"`), an `enabled` flag (mute/unmute), and a `readyState`.

```
MediaStream
 ├── MediaStreamTrack (kind: "audio")  ← microphone
 └── MediaStreamTrack (kind: "video")  ← camera
```

You can mix and match: add a screen-share track to an existing stream, replace the camera track mid-call without renegotiating, or clone tracks. Muting is just `track.enabled = false` — the track still flows but sends silence/black, which keeps timing intact. `track.stop()` fully releases the hardware (camera light goes off).

### Q5. [Practical] Mute the microphone and toggle the camera without tearing down the call.

Toggle `track.enabled`. This is instant, requires no renegotiation, and keeps the RTP stream alive (sending silence/black frames) so the connection stays warm.

```javascript
function toggleAudio(stream) {
  const audioTrack = stream.getAudioTracks()[0];
  audioTrack.enabled = !audioTrack.enabled;
  return audioTrack.enabled; // false = muted
}

function toggleVideo(stream) {
  const videoTrack = stream.getVideoTracks()[0];
  videoTrack.enabled = !videoTrack.enabled;
  return videoTrack.enabled;
}
```

Contrast with `track.stop()`, which releases the device entirely. Use `enabled` for mute, `stop()` only when you truly want the hardware free (and you'd need a fresh `getUserMedia` to resume).

### Q6. [Theory] What is RTCPeerConnection responsible for?

`RTCPeerConnection` is the workhorse. It manages the entire lifecycle of a peer-to-peer connection:

- **SDP negotiation** — generating offers/answers describing media and capabilities.
- **ICE** — gathering candidate addresses and performing connectivity checks.
- **DTLS handshake** — establishing keys for encryption.
- **Media transport** — sending/receiving SRTP packets, including codec selection, packetization, RTCP feedback, retransmission, and FEC.
- **Congestion control** — estimating available bandwidth and adapting bitrate.

You add tracks with `addTrack()`, listen for remote media on the `track` event, and react to state changes (`connectionstatechange`, `iceconnectionstatechange`).

### Q7. [Coding] Set up a minimal RTCPeerConnection and wire the events you must handle.

```javascript
const pc = new RTCPeerConnection({
  iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
});

// 1. Local ICE candidates → send to the remote peer via your signaling channel.
pc.onicecandidate = ({ candidate }) => {
  if (candidate) signaling.send({ type: 'ice', candidate });
};

// 2. Remote media arrives here. Attach it to a <video> element.
pc.ontrack = ({ streams: [remoteStream] }) => {
  document.querySelector('#remoteVideo').srcObject = remoteStream;
};

// 3. Monitor connection health.
pc.onconnectionstatechange = () => {
  console.log('state:', pc.connectionState); // connecting → connected → ...
  if (pc.connectionState === 'failed') restartIce(pc);
};

// 4. Add local media so it gets sent to the peer.
localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
```

The four things you *must* handle: `onicecandidate` (trickle candidates out), `ontrack` (render incoming media), connection-state changes (recover on failure), and adding your tracks.

### Q8. [Theory] What is SDP, and what is the offer/answer model?

SDP (Session Description Protocol, RFC 8866) is a plain-text format describing a media session: which codecs each side supports, encryption fingerprints, ICE credentials, bandwidth limits, and how media is multiplexed.

The negotiation is a two-step **offer/answer** exchange:

1. The caller creates an **offer** (`createOffer()`) listing everything it *can* do, sets it as its local description, and sends it.
2. The callee sets that as its remote description, creates an **answer** (`createAnswer()`) that picks the intersection of capabilities, sets it locally, and sends it back.
3. The caller sets the answer as its remote description. Negotiation complete.

```
m=audio 9 UDP/TLS/RTP/SAVPF 111 63
a=rtpmap:111 opus/48000/2          ← codec the side supports
a=fingerprint:sha-256 AB:CD:...    ← DTLS cert fingerprint (security)
a=ice-ufrag:F7gI                   ← ICE username fragment
a=setup:actpass                    ← DTLS role
```

You almost never edit SDP by hand; the browser generates it. Occasionally you "munge" it to force a codec or bitrate, but prefer the `RTCRtpSender` `setParameters` and transceiver APIs instead.

### Q9. [Coding] Implement the caller side of the offer/answer flow.

```javascript
async function makeCall(pc, signaling) {
  // Caller creates the offer and shares it.
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  signaling.send({ type: 'offer', sdp: pc.localDescription });

  // Later, the answer comes back over signaling.
  signaling.on('answer', async ({ sdp }) => {
    await pc.setRemoteDescription(sdp);
  });

  // Trickled remote ICE candidates.
  signaling.on('ice', async ({ candidate }) => {
    try { await pc.addIceCandidate(candidate); }
    catch (e) { console.warn('addIceCandidate failed', e); }
  });
}
```

The callee mirrors this: on receiving the offer, `setRemoteDescription(offer)` → `createAnswer()` → `setLocalDescription(answer)` → send the answer back.

### Q10. [Theory] Why do peers behind NAT need help connecting, and what is STUN?

Most devices sit behind a NAT (Network Address Translation) router or firewall and have a private IP (e.g. `192.168.x.x`) that is meaningless on the public internet. A peer advertising only its private address can't be reached from outside.

**STUN** (Session Traversal Utilities for NAT) solves the first half. A peer sends a request to a public STUN server, which replies with the public IP:port the request *appeared* to come from — its "server-reflexive" address. The peer advertises this so the other side can target it.

```
Peer (192.168.1.5:50000)  --->  NAT  --->  STUN server
                                              |
        "you look like 203.0.113.7:62000" <---+
```

STUN is cheap (a quick request/response) and the server never touches your media. It works for the majority of NAT types but fails for symmetric NATs and strict firewalls — which is where TURN comes in.

### Q11. [Theory] What is TURN and when is it needed?

**TURN** (Traversal Using Relays around NAT) is the fallback when direct P2P is impossible — typically symmetric NATs, strict corporate firewalls, or networks that block UDP. The TURN server acts as a **relay**: both peers send media *to* the TURN server, which forwards it to the other side.

```
Peer A  ---->  TURN relay  ---->  Peer B
        <----              <----
```

This means the media is no longer truly peer-to-peer — it transits your relay, adding latency and consuming your bandwidth/cost. TURN is the safety net, not the default: well-deployed systems serve ~80–90% of calls over direct or STUN-assisted paths and only ~10–20% fall back to TURN. You must run (or pay for) TURN servers — `coturn` is the standard open-source option. TURN credentials should be short-lived (REST API time-limited credentials), never static, because relay bandwidth is expensive.

### Q12. [Theory] What is ICE and how does it tie STUN and TURN together?

**ICE** (Interactive Connectivity Establishment, RFC 8445) is the framework that orchestrates everything. Each peer gathers **candidates** — possible ways to be reached:

- **host** candidates: local IP:port (LAN, loopback).
- **srflx** (server-reflexive): public address discovered via STUN.
- **relay**: an address on a TURN server.

Peers exchange candidates via signaling, form **candidate pairs** (one local × one remote), and run connectivity checks (STUN binding requests) on each pair. ICE prioritizes pairs — host > srflx > relay — and picks the highest-priority pair that actually works. This is the "nominated" pair used for media.

```
Candidate priority (high → low):
  host (direct LAN)  >  srflx (via STUN)  >  relay (via TURN)
```

ICE handles the messy reality of NATs automatically; you just supply `iceServers` (STUN + TURN URLs) in the `RTCPeerConnection` config.

### Q13. [Practical] Configure iceServers with both STUN and TURN.

```javascript
const pc = new RTCPeerConnection({
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    {
      urls: ['turn:turn.example.com:3478?transport=udp',
             'turns:turn.example.com:5349?transport=tcp'], // turns = TLS, helps through firewalls
      username: '1719795600:alice',          // time-limited username (expiry:user)
      credential: 'hMAC_base64_signature'    // HMAC-SHA1 of username with shared secret
    }
  ],
  iceTransportPolicy: 'all'   // use 'relay' to force TURN (e.g. for testing relay path)
});
```

Provide both UDP and TCP/TLS TURN URLs: UDP is preferred for latency, but `turns:` over TCP/443 punches through restrictive firewalls that block everything else. Generate TURN credentials server-side using the time-limited (REST) scheme so they expire.

### Q14. [Theory] What is "trickle ICE" and why is it better than gathering all candidates first?

Without trickle, a peer gathers **all** ICE candidates (including slow TURN allocations) before sending its offer/answer — adding seconds of delay to call setup. **Trickle ICE** (RFC 8838) sends each candidate to the remote peer *as soon as it's discovered*, in parallel with the offer/answer exchange.

```
Without trickle:  gather all candidates (2–5s) → send offer → connect
With trickle:     send offer immediately → stream candidates as found → connect ASAP
```

The browser does this automatically: you just forward each `onicecandidate` event over signaling and call `addIceCandidate()` on the other side. A `null` candidate signals end-of-gathering. Trickle is now standard and dramatically cuts time-to-first-frame. Your signaling protocol must support sending candidates after the offer/answer.

### Q15. [Theory] How does WebRTC encrypt media? What is DTLS-SRTP?

Encryption is **mandatory** — there is no opt-out. WebRTC uses **DTLS-SRTP**:

- **DTLS** (Datagram TLS) runs a TLS-style handshake over UDP between the two peers to authenticate and derive shared keys. Each peer's certificate fingerprint is included in its SDP, so the SDP exchange (over your trusted signaling channel) authenticates the DTLS certificates — preventing man-in-the-middle.
- **SRTP** (Secure RTP) uses those derived keys to encrypt and authenticate the actual media packets.

```
1. SDP exchange carries cert fingerprints (a=fingerprint:sha-256 ...)
2. DTLS handshake → verifies certs match fingerprints → derives keys
3. SRTP encrypts every media packet with those keys
```

Data channels are encrypted with DTLS directly (SCTP-over-DTLS). The takeaway: media is encrypted hop-by-hop in P2P; the only way to MITM is to compromise the signaling channel — which is why signaling must run over TLS (WSS/HTTPS).

### Q16. [Theory] What is RTCDataChannel and how does it differ from WebSockets?

`RTCDataChannel` sends arbitrary application data (chat, file transfers, game state, control messages) **peer-to-peer** over the same DTLS-secured connection as media. It runs over **SCTP** tunneled in DTLS.

Differences from WebSockets:

| | RTCDataChannel | WebSocket |
|---|---|---|
| Path | Peer-to-peer (or via TURN) | Client ↔ server |
| Transport | SCTP/DTLS/UDP | TCP |
| Ordering | Configurable (ordered or not) | Always ordered |
| Reliability | Configurable (reliable or unreliable) | Always reliable |
| Latency | Lower (no server hop, can skip retransmits) | Higher (TCP head-of-line blocking) |

The key superpower is **configurable reliability**: for a multiplayer game you can choose unordered + unreliable (fire-and-forget, no head-of-line blocking), which TCP-based WebSockets can never offer.

### Q17. [Coding] Open a reliable and an unreliable data channel.

```javascript
// Reliable, ordered — like TCP. Good for chat, file transfer, control messages.
const chat = pc.createDataChannel('chat');
chat.onopen  = () => chat.send('hello');
chat.onmessage = (e) => console.log('received:', e.data);

// Unreliable, unordered — like UDP. Good for real-time game position updates
// where a dropped packet is irrelevant because a newer one is coming.
const gameState = pc.createDataChannel('game', {
  ordered: false,
  maxRetransmits: 0   // never retransmit; drop and move on
});

// The remote side receives the channel via an event:
pc.ondatachannel = ({ channel }) => {
  channel.onmessage = (e) => handle(channel.label, e.data);
};
```

`maxPacketLifeTime` (ms) is an alternative to `maxRetransmits` — retransmit only within a time budget. Set exactly one of the two.

### Q18. [Theory] Name the common audio and video codecs in WebRTC and their roles.

**Audio:**
- **Opus** — the default and near-universal choice. Variable bitrate 6–510 kbps, excellent quality, low latency, handles both speech and music, built-in FEC and DTX (discontinuous transmission for silence).
- G.711 (PCMU/PCMA) — legacy, used for telephony/SIP interop.

**Video:**
- **VP8** — original WebRTC mandatory codec, royalty-free, universally supported.
- **VP9** — better compression than VP8, supports SVC (scalable video coding).
- **H.264** — hardware-accelerated on most devices (saves battery/CPU), required for some interop; royalty considerations.
- **AV1** — newest, best compression (~30% better than VP9), royalty-free, supports SVC; encoding is CPU-heavy so often used with hardware support or for screen content. Increasingly the default for new deployments in 2026.

Codec negotiation happens in SDP — both sides advertise their lists and the intersection (in preference order) wins.

## 🟡 Intermediate (3–7 yrs)

### Q19. [Theory] Compare mesh, SFU, and MCU topologies for group calls.

```
MESH (no server media)        SFU (selective forward)     MCU (mix/composite)
                                                          
  A --- B                        A     B                     A   B
  | \ / |                         \   /                        \ /
  |  X  |                          \ /                        [MCU] mixes
  | / \ |                         [SFU] forwards               / \
  C --- D                          / \                        C   D
                                  C   D
```

- **Mesh**: every peer connects directly to every other. N participants → each sends N−1 streams. No server media cost, lowest latency, but upload bandwidth and CPU explode past ~4 people. O(N²) connections total. Only viable for tiny calls.
- **SFU** (Selective Forwarding Unit): each peer sends **one** upstream to the server, which *forwards* (does not decode) copies to other participants. Each peer uploads once, downloads N−1. Scales to dozens/hundreds. The dominant production architecture. Server CPU is modest (just routing packets).
- **MCU** (Multipoint Control Unit): the server **decodes all streams, composites them into one**, and re-encodes a single stream per participant. Each peer uploads and downloads exactly one stream — minimal client cost — but the server CPU is enormous (decode+encode per call) and it adds latency. Used for legacy interop, recording, or constrained clients.

In 2026, SFU is the default for nearly all scalable group calling; MCU is niche.

### Q20. [Theory] Why does mesh topology fall apart, quantitatively?

In a mesh, each participant must **encode and upload its stream once per other participant** (each peer connection is independent) and decode every incoming stream. For N participants:

- Outbound streams per peer: N−1 (each separately encoded/encrypted).
- Total connections: N(N−1)/2.

At 720p ~2 Mbps per stream:
- 3 people: each uploads ~4 Mbps — fine.
- 5 people: each uploads ~8 Mbps — most home uplinks (often 5–10 Mbps) are saturated.
- 10 people: ~18 Mbps up + 10 decodes — not happening on consumer hardware.

```
N=2:  1 stream up    N=4:  3 streams up    N=8:  7 streams up
N=10: 9 streams up + 9 simultaneous decodes → CPU + uplink death
```

So mesh is capped at ~3–4 participants. Beyond that you need an SFU so each client uploads only once.

### Q21. [Theory] How does an SFU work, and why is it cheaper than an MCU?

An SFU receives each participant's RTP packets and **forwards them, unmodified, to the subscribers** who want that stream. It does not decode or re-encode media — it operates at the packet-routing level (plus rewriting some RTP headers, handling RTCP feedback, and selecting which simulcast layer to forward).

```
Sender → [SFU: decrypt SRTP, route packets, re-encrypt] → many receivers
         (no pixel-level decode/encode)
```

Because it never touches the actual video frames, an SFU's CPU cost is roughly proportional to bandwidth, not resolution × participants. One SFU box can handle thousands of forwarded streams. An MCU, by contrast, must decode every input and encode a fresh composite per output — orders of magnitude more CPU, and it adds encode/decode latency. The SFU's trade-off: each receiver gets N−1 separate decodes (client does more work), but that's a far better split for scaling.

### Q22. [Theory] What is simulcast and what problem does it solve?

In a group call, receivers have wildly different conditions — one on fiber viewing fullscreen, another on 3G viewing a thumbnail. If the sender sends a single high-bitrate stream, the SFU can't tailor it per receiver.

**Simulcast** has the sender encode the **same video at multiple resolutions/bitrates simultaneously** (e.g. 180p, 360p, 720p) and send all of them to the SFU. The SFU then **selects which layer to forward** to each receiver based on that receiver's bandwidth and the size it's rendering.

```
Sender encodes 3 layers:        SFU forwards per receiver:
  ┌─ 720p @ 1.5 Mbps ─┐           → fiber/fullscreen viewer: 720p
  ├─ 360p @ 0.5 Mbps ─┤  → SFU →  → tablet/grid viewer:      360p
  └─ 180p @ 0.15 Mbps─┘           → mobile/thumbnail viewer:  180p
```

The sender's cost rises (encode 3× and upload all layers), but the SFU gains the ability to adapt per-receiver without re-encoding. Simulcast is the standard mechanism that makes SFUs adaptive. Enabled via `sendEncodings` when adding the transceiver.

### Q23. [Coding] Enable simulcast with three layers when adding a video track.

```javascript
pc.addTransceiver(videoTrack, {
  direction: 'sendonly',
  sendEncodings: [
    { rid: 'l', scaleResolutionDownBy: 4, maxBitrate: 150_000 },  // ~180p
    { rid: 'm', scaleResolutionDownBy: 2, maxBitrate: 500_000 },  // ~360p
    { rid: 'h', scaleResolutionDownBy: 1, maxBitrate: 1_500_000 } // ~720p
  ]
});
```

`rid` (RTP stream ID) labels each layer so the SFU can address them. `scaleResolutionDownBy` divides the resolution; `maxBitrate` caps each layer. Order matters — list lowest-quality first by convention. The browser encodes all three from the single source track. Note: simulcast requires SFU cooperation; in a plain 1:1 connection there's no one to select layers.

### Q24. [Theory] What is SVC and how does it differ from simulcast?

**SVC** (Scalable Video Coding) encodes a single bitstream containing multiple **layers nested inside one stream**: a base layer plus enhancement layers for higher resolution (spatial), frame rate (temporal), or quality. An SFU can **drop** enhancement layers per receiver without re-encoding, because lower layers are decodable on their own.

```
Simulcast: 3 independent streams      SVC: 1 layered stream
  [720p] [360p] [180p]                  [base 180p][+360p][+720p]
  (3× encode cost, 3× upload)           (1 encode, drop layers to scale down)
```

Key differences:
- **Encoding cost**: SVC encodes once (cheaper for the sender than simulcast's N encodes); temporal SVC is essentially free.
- **Adaptation granularity**: SVC can drop frames (temporal layers) very smoothly; switching is seamless because layers reference each other.
- **Codec support**: VP9, AV1, and H.265 support rich SVC; VP8 only does temporal. AV1's SVC is a big reason it's favored in 2026.

SVC is generally superior where the codec supports it; simulcast remains common for H.264 and broad compatibility. Many systems use temporal SVC even within simulcast layers.

### Q25. [Theory] How does WebRTC estimate available bandwidth and adapt?

WebRTC continuously estimates the network's capacity and adjusts encoder bitrate to fit, avoiding congestion. The modern algorithm is **GCC** (Google Congestion Control), which combines:

- **Delay-based estimation**: monitors one-way delay *variation* (inter-arrival time of packets vs. send time). Rising delay = a queue is building = back off **before** loss happens.
- **Loss-based estimation**: classic AIMD on packet loss as a secondary signal.

Feedback comes via RTCP, especially **transport-wide congestion control (TWCC)**, where the receiver reports arrival times of every packet so the sender computes precise delay gradients.

```
Sender encodes at bitrate B
   → packets arrive with growing inter-arrival delay
   → GCC infers queue building → reduces B
   → delay stabilizes → cautiously probe higher again
```

The estimate feeds the encoder's target bitrate and, in simulcast/SVC, decisions about which layers to send. The newer **L4S** (Low Latency, Low Loss, Scalable throughput) with ECN is being adopted in 2026 for even tighter control. The result: video quality scales smoothly up and down with network conditions instead of stalling.

### Q26. [Theory] What is a jitter buffer and why is it necessary?

Packets traverse the network with variable delay — **jitter**. Packet 2 might arrive before packet 1, or with an irregular gap. If you played media the instant each packet arrived, you'd get choppy, out-of-order garbage.

The **jitter buffer** is a small queue on the receive side that holds incoming packets briefly, **reorders them**, and releases them to the decoder at a smooth, steady cadence.

```
Network (jittery arrivals):   pkt1   pkt3 pkt2      pkt5 pkt4
                                |      \  /            \  /
Jitter buffer (reorder+delay): [====== buffer ======]
Decoder (smooth playout):     pkt1 pkt2 pkt3 pkt4 pkt5  →  steady
```

It's **adaptive**: a larger buffer absorbs more jitter but adds latency; a smaller buffer is lower-latency but risks underruns (gaps). WebRTC tunes buffer depth dynamically based on measured jitter. Audio buffers are typically tens of ms; video can be larger. This is the classic latency-vs-smoothness trade-off, managed automatically — but it's why you can't have *both* zero latency and perfectly smooth playout on a jittery link.

### Q27. [Theory] How does WebRTC handle packet loss for media (NACK, FEC, PLI/FIR)?

Several complementary mechanisms, chosen by latency budget:

- **NACK** (Negative Acknowledgement): the receiver asks the sender to **retransmit** a specific lost packet. Cheap, but adds a round-trip of latency — only useful if there's time before playout.
- **FEC** (Forward Error Correction): the sender adds **redundant** data so the receiver can reconstruct lost packets *without* a retransmit. Costs bandwidth always, but zero added latency. Opus has in-band FEC; video uses ULPFEC/FlexFEC or RED.
- **PLI / FIR** (Picture Loss Indication / Full Intra Request): when video is too corrupted to recover, the receiver asks the sender for a fresh **keyframe** (intra frame) to resync. Expensive (keyframes are large) so used sparingly.

```
Small loss, time to spare → NACK retransmit
Loss likely + tight latency → FEC redundancy (pay bandwidth up front)
Unrecoverable video → PLI/FIR → keyframe (resets decode)
```

The mix is tuned per stream: audio leans on FEC + concealment, video on NACK + occasional keyframes.

### Q28. [Practical] Read connection statistics with getStats() to diagnose a bad call.

`pc.getStats()` returns a snapshot of low-level metrics — the primary tool for debugging quality issues.

```javascript
async function reportQuality(pc) {
  const stats = await pc.getStats();
  for (const report of stats.values()) {
    if (report.type === 'inbound-rtp' && report.kind === 'video') {
      console.log({
        packetsLost: report.packetsLost,
        jitter: report.jitter,                  // seconds
        framesPerSecond: report.framesPerSecond,
        framesDropped: report.framesDropped,
        freezeCount: report.freezeCount,
        totalFreezesDuration: report.totalFreezesDuration
      });
    }
    if (report.type === 'candidate-pair' && report.nominated) {
      console.log('RTT:', report.currentRoundTripTime,           // seconds
                  'available recv bw:', report.availableIncomingBitrate);
    }
  }
}
```

Key signals: rising `packetsLost`/`jitter` → network trouble; growing `freezeCount` → decoder starving; high `currentRoundTripTime` → latency; falling `availableIncomingBitrate` → congestion. Production apps poll this every few seconds and ship it to an analytics backend.

### Q29. [Practical] Switch the camera (or share screen) mid-call without renegotiating.

Use `RTCRtpSender.replaceTrack()` — it swaps the media source on an existing sender without a new SDP exchange, so there's no renegotiation hiccup.

```javascript
async function switchToScreenShare(pc, localVideoEl) {
  const screenStream = await navigator.mediaDevices.getDisplayMedia({ video: true });
  const screenTrack = screenStream.getVideoTracks()[0];

  // Find the sender currently sending video and swap its track.
  const videoSender = pc.getSenders().find(s => s.track && s.track.kind === 'video');
  await videoSender.replaceTrack(screenTrack);   // seamless, no renegotiation
  localVideoEl.srcObject = screenStream;

  // When the user clicks the browser "stop sharing" UI:
  screenTrack.onended = async () => {
    const cam = (await navigator.mediaDevices.getUserMedia({ video: true })).getVideoTracks()[0];
    await videoSender.replaceTrack(cam);
  };
}
```

`replaceTrack` is the right tool for camera switching, screen sharing, and applying processed (e.g. background-blurred) tracks. Adding/removing tracks entirely *does* trigger renegotiation; replacing does not.

### Q30. [Theory] What is "perfect negotiation" and the glare problem?

**Glare** happens when both peers try to create an offer at the same time — each gets a remote offer while it has a pending local offer, and the state machine breaks. This is common when either side can start renegotiation (e.g. both add a screen share simultaneously).

**Perfect negotiation** (a W3C-recommended pattern) resolves glare by assigning roles: one peer is **polite**, the other **impolite**.

```
On incoming offer while we have a pending local offer (collision):
  - Impolite peer: ignore the incoming offer (we win, keep ours).
  - Polite peer:   rollback our offer, accept theirs (we yield).
```

```javascript
pc.onnegotiationneeded = async () => {
  makingOffer = true;
  await pc.setLocalDescription();            // implicit createOffer
  signaling.send({ description: pc.localDescription });
  makingOffer = false;
};

async function onRemoteDescription(description) {
  const collision = description.type === 'offer'
    && (makingOffer || pc.signalingState !== 'stable');
  if (collision && !polite) return;          // impolite ignores
  if (collision && polite) await pc.setLocalDescription({ type: 'rollback' });
  await pc.setRemoteDescription(description);
  if (description.type === 'offer') {
    await pc.setLocalDescription();          // create + set answer
    signaling.send({ description: pc.localDescription });
  }
}
```

This makes either side able to (re)negotiate safely without races.

### Q31. [Theory] What's the difference between iceConnectionState and connectionState?

`RTCPeerConnection` exposes several state machines; two matter most:

- **iceConnectionState** — tracks only the ICE/connectivity layer: `new → checking → connected → completed → disconnected → failed`. `disconnected` is often transient (a few lost packets); `failed` means ICE gave up.
- **connectionState** — an aggregate of ICE *and* DTLS state: `new → connecting → connected → disconnected → failed → closed`. This is the one you should use for overall health because it reflects whether media can actually flow (DTLS done = encryption ready).

```
connectionState = roll-up of:
   iceConnectionState  (can packets get through?)
 + dtlsTransportState  (are we encrypted?)
```

Practical rule: drive your UI ("Connected"/"Reconnecting") off `connectionState`, and trigger an **ICE restart** when you hit `failed`. Treat a brief `disconnected` with a grace period before declaring trouble.

### Q32. [Coding] Recover a broken connection with an ICE restart.

When `connectionState` hits `failed` (e.g. the user switched from Wi-Fi to cellular and the IP changed), an **ICE restart** re-gathers candidates and re-establishes connectivity *without* tearing down the whole peer connection or media.

```javascript
async function restartIce(pc, signaling) {
  // Generate a fresh offer with new ICE credentials.
  const offer = await pc.createOffer({ iceRestart: true });
  await pc.setLocalDescription(offer);
  signaling.send({ type: 'offer', sdp: pc.localDescription });
  // Remote side answers as normal; ICE re-gathers and reconnects.
}

pc.onconnectionstatechange = () => {
  if (pc.connectionState === 'failed') restartIce(pc, signaling);
};
```

Modern browsers also support `pc.restartIce()` which triggers the `negotiationneeded` event to do the same. ICE restart preserves the DTLS session and media tracks, so it's far less disruptive than rebuilding the connection — the call survives a network change with a brief blip.

### Q33. [Practical] Apply real-time effects (background blur) using Insertable Streams / MediaStreamTrackProcessor.

The Insertable Streams API exposes raw `VideoFrame`s as a `ReadableStream`, lets you transform each frame (e.g. via a `TransformStream`, often offloaded to a Web Worker + WebGL/WASM model), and writes them back.

```javascript
const [track] = (await navigator.mediaDevices.getUserMedia({ video: true })).getVideoTracks();
const processor = new MediaStreamTrackProcessor({ track });
const generator = new MediaStreamTrackGenerator({ kind: 'video' });

const transformer = new TransformStream({
  async transform(frame, controller) {
    const processed = await blurBackground(frame); // your ML/WebGL pipeline → VideoFrame
    frame.close();                                 // MUST close to avoid leaking GPU memory
    controller.enqueue(processed);
  }
});

processor.readable.pipeThrough(transformer).pipeTo(generator.writable);

// Send the processed track instead of the raw camera:
const processedStream = new MediaStream([generator]);
videoSender.replaceTrack(generator);
```

Critical: always `frame.close()` each `VideoFrame` — they hold GPU memory and leaking them crashes the tab. Heavy processing belongs in a Worker to keep the main thread responsive.

### Q34. [Theory] What is the latency budget of a WebRTC call, end to end?

"Glass-to-glass" latency is the sum of several stages. A healthy WebRTC call lands in the **100–300 ms** range:

```
Capture       ~5–15 ms   (sensor + frame assembly)
Encode        ~10–30 ms  (codec, depends on complexity/hardware)
Network (RTT/2) ~10–150 ms (geography, congestion; the big variable)
Jitter buffer  ~20–80 ms  (adaptive; absorbs jitter)
Decode        ~5–20 ms
Render        ~5–15 ms
─────────────────────────
Total         ~100–300 ms typical
```

Below ~150 ms feels truly conversational; above ~400 ms people start talking over each other. The two biggest, most controllable levers are **network RTT** (place SFUs/TURN close to users, prefer UDP) and the **jitter buffer** (trade smoothness for latency). An SFU adds one extra hop (~adding the server's RTT); a relay (TURN) or MCU adds more. Compare this with HLS/LL-HLS at 2–10 s — WebRTC is the choice when interactivity matters.

### Q35. [Theory] Why does WebRTC prefer UDP, and when does it use TCP?

WebRTC media runs over UDP because **TCP is wrong for real-time media**: TCP guarantees in-order, reliable delivery, which means a single lost packet causes **head-of-line blocking** — everything behind it waits for the retransmit. For live video, a packet that arrives late is *useless* (the moment passed), so you'd rather drop it and move on. UDP lets WebRTC make that choice itself (via NACK/FEC selectively).

```
TCP:  lost pkt → stall entire stream until retransmit → growing latency
UDP:  lost pkt → conceal/FEC/skip → stay real-time
```

WebRTC falls back to **TCP (or TLS over TCP/443)** only when UDP is blocked — typically via a TURN server over TCP, common on locked-down corporate networks. It works but with worse latency due to that head-of-line blocking. So: UDP by default, TCP as a last-resort reachability fallback.

## 🟠 Advanced (8–12 yrs)

### Q36. [Theory] How do you scale an SFU to thousands of participants in one session?

A single SFU is bounded by CPU and especially **egress bandwidth**. A 10,000-viewer broadcast can't have everyone subscribe to one box. Techniques:

- **Cascading / relay SFUs**: chain SFUs in a tree. The origin SFU forwards each publisher's stream to regional/edge SFUs, which fan out to local subscribers. Egress is distributed; subscribers connect to a nearby SFU (lower RTT).

```
       Publisher
          │
      [Origin SFU]
        /      \
  [Edge SFU EU] [Edge SFU US]
    / | \         / | \
  viewers...    viewers...
```

- **Layer selection at the edge**: each edge forwards only the simulcast/SVC layer each viewer needs.
- **Sharding by session**: route different rooms to different SFU instances (consistent hashing on room ID); a coordination layer tracks which SFU hosts which room.
- **Pub/sub decoupling**: for huge broadcasts (1 speaker, many viewers), it's effectively a CDN-like tree; some systems hand off the long tail to LL-HLS/WHIP-WHEP for massive audiences while keeping interactive participants on WebRTC.
- **Active-speaker / pagination**: don't forward all N videos to everyone — forward only the few visible/active streams (the SFU drops the rest), bounding per-client load regardless of room size.

### Q37. [Theory] In a large meeting, you can't send everyone's video to everyone. How do you decide what to forward?

The SFU must bound each subscriber's incoming streams independently of room size. Standard strategies, usually combined:

- **Active speaker detection**: using audio levels (the `audio-level` RTP header extension / RFC 6464), the SFU ranks who's talking and prioritizes forwarding their video. Dominant speaker(s) get high-quality layers; everyone else gets a thumbnail or nothing.
- **Last-N forwarding**: forward only the last N participants who spoke (e.g. N=5–9 video tiles), regardless of total participants. The "speaker view" / grid shows these.
- **Viewport / subscription signaling**: the client tells the SFU which participants are actually visible (on-screen, not scrolled away) and at what render size; the SFU forwards only those, at the matching simulcast layer.
- **Per-subscriber bitrate budget**: given the receiver's estimated downlink, the SFU allocates the budget across visible streams — dominant speaker gets 720p, others get 180p — and demotes layers when the budget shrinks.

```
Room of 200, viewer sees 9 tiles:
  SFU forwards 9 video streams (1 high + 8 low) + 200 audio (audio is cheap)
  → load is O(tiles), not O(participants)
```

Audio is forwarded for everyone (or mixed) since it's tiny; video is aggressively pruned.

### Q38. [Theory] Compare mediasoup, Janus, LiveKit, and Pion for building server-side media infrastructure.

All are SFUs (or SFU toolkits); they differ in abstraction level and language:

- **mediasoup** (Node.js/C++/Rust core): a low-level **SFU library**, not a turnkey server. You build your own signaling and orchestration around its router/transport/producer/consumer primitives. Extremely flexible and high-performance; favored when you need precise control. Steeper build effort.
- **Janus** (C): a mature, plugin-based **WebRTC gateway/server**. Plugins for SFU video rooms, streaming, SIP gateway, recording. Battle-tested, flexible, but C plugin development and scaling orchestration are on you.
- **LiveKit** (Go): a higher-level, **batteries-included** platform — SFU plus SDKs, room management, auth (JWT), recording/egress, simulcast, and built-in horizontal scaling/cascading. Open source with a cloud offering. Fastest path to a production app; less low-level control. Popular in 2026, including for AI voice agents.
- **Pion** (Go): a pure-Go **WebRTC implementation** (like libwebrtc but in Go). A toolkit to build custom media servers/SFUs from scratch — maximum control, you assemble everything.

```
Control ↑                                    Convenience ↑
Pion ── mediasoup ── Janus ──────────────── LiveKit
(build it all)   (SFU lib)   (gateway+plugins)  (full platform)
```

Choose LiveKit to ship fast, mediasoup/Pion when you need bespoke routing, Janus for its gateway/SIP heritage.

### Q39. [Theory] What are WHIP and WHEP and why do they matter?

**WHIP** (WebRTC-HTTP Ingestion Protocol) and **WHEP** (WebRTC-HTTP Egress Protocol) are IETF standards that define **HTTP-based signaling** for WebRTC, replacing the bespoke WebSocket signaling everyone used to hand-roll for one-way streaming.

- **WHIP**: a publisher POSTs its SDP offer to an endpoint and gets an answer back — standardized *ingest*. Lets OBS, hardware encoders, and broadcast tools push WebRTC to any compliant server without custom code.
- **WHEP**: the mirror for *playback* — a viewer GETs/POSTs to subscribe to a stream.

```
Encoder/OBS ──WHIP (HTTP POST SDP)──▶ Media server ──WHEP──▶ Viewers
```

Why they matter: they make WebRTC ingest/egress **interoperable** like RTMP was, enabling sub-second live streaming with off-the-shelf tooling. By 2026 they're widely supported (OBS, cloud media services, CDNs) and are the standard way to bridge broadcast workflows into WebRTC for low-latency streaming at scale. They don't replace full-mesh signaling for symmetric conferencing — they target the publish/subscribe (one-to-many) case.

### Q40. [Theory] How would you architect call recording for an SFU-based system?

Recording shouldn't run in the browser (unreliable, client-side). Standard approaches:

- **Server-side compositing (egress)**: a dedicated recorder joins the room as a participant (often a **headless Chromium** rendering the actual UI layout, captured via screen+audio), or a media pipeline composites the SFU's streams. Produces a single MP4 with the meeting layout. CPU-heavy (it's effectively an MCU for the recording) but gives a faithful, ready-to-play file. LiveKit Egress and Janus do this.
- **Track-level recording**: dump each participant's RTP/decoded track to separate files, plus a timing manifest. Cheap (no re-encode), flexible for post-processing, but you must composite later if you want a single video.

```
SFU streams ──▶ [Recorder]
                 ├─ Option A: headless browser renders layout → encode → MP4
                 └─ Option B: per-track files + manifest → composite offline
```

Considerations: store to object storage (S3), handle the recorder as a scalable pool of workers, account for the extra subscriber load on the SFU, manage consent/compliance, and decide live (during call) vs. post-processing. For audio-only or transcription, you can tap the mixed/forwarded audio cheaply.

### Q41. [Theory] How does simulcast interact with bandwidth estimation on the receive side and at the SFU?

Two estimation loops cooperate:

1. **Receiver downlink estimate** (REMB/TWCC from each subscriber → SFU): each viewer reports its available downlink. The SFU uses this to pick which simulcast layer to forward to that viewer. Downlink drops → SFU steps down from 720p to 360p to 180p for that subscriber only.
2. **Sender uplink estimate** (publisher's GCC): the publisher estimates *its* uplink and may stop sending the top layer entirely if its upload is constrained — in which case that layer is unavailable to *all* subscribers no matter their downlink.

```
Publisher uplink ↓  → drops 720p layer → SFU can only offer ≤360p to everyone
Subscriber downlink ↓ → SFU forwards lower layer to THAT subscriber only
```

The SFU is the matchmaker: it knows which layers exist (constrained by publisher uplink) and which each subscriber can afford (their downlink), and forwards the best feasible layer per subscriber. This decoupling is the whole point of simulcast — one congested receiver doesn't degrade everyone, and one congested sender gracefully caps the ceiling. Layer-switching also triggers keyframe requests so the new layer can be decoded.

### Q42. [Behavioral] Tell me about a time you debugged a hard real-time media issue in production.

Use a structured STAR answer grounded in WebRTC specifics. A strong example:

- **Situation**: a subset of users (clustered on a particular ISP / corporate network) reported one-way audio or calls that connected then froze after ~30 seconds.
- **Task**: identify root cause without reproducing it locally — it only happened on certain networks.
- **Action**: instrumented `getStats()` to ship `inbound-rtp`/`candidate-pair`/`selected candidate type` to analytics. Found affected calls were (a) falling back to TURN-over-TCP (UDP blocked) and (b) `availableIncomingBitrate` collapsing, plus rising `freezeCount`. Correlated with a too-low default TURN bandwidth cap and a missing `turns:` (TLS/443) URL, so some firewalls blocked even the TCP TURN. Added the `turns:443` candidate, raised relay limits, and tuned the jitter buffer.
- **Result**: connection success on those networks went from ~70% to ~99%; froze-call rate dropped sharply. Added a permanent dashboard for candidate-type distribution and relay fallback rate.

What this signals: you reach for `getStats()` and aggregate metrics rather than guessing, you understand the NAT/TURN/firewall layer, and you fix systemically (monitoring) not just the one ticket.

### Q43. [Practical] Detect and handle a participant whose network has degraded, server-side.

The SFU watches RTCP feedback and `getStats`-equivalent transport metrics per peer, then degrades that peer gracefully without harming others.

```javascript
// Pseudocode for an SFU-side adaptation loop (e.g. mediasoup consumer).
function adaptConsumer(consumer, transportStats) {
  const { availableOutgoingBitrate, packetLossRate, rtt } = transportStats;

  if (packetLossRate > 0.10 || availableOutgoingBitrate < 300_000) {
    consumer.setPreferredLayers({ spatialLayer: 0, temporalLayer: 1 }); // drop to lowest video
  } else if (availableOutgoingBitrate < 800_000) {
    consumer.setPreferredLayers({ spatialLayer: 1 });                   // mid layer
  } else {
    consumer.setPreferredLayers({ spatialLayer: 2 });                   // top layer
  }

  if (packetLossRate > 0.25 && rtt > 400) {
    consumer.pause();            // suspend video, keep audio (audio-only fallback)
    notifyClient(consumer.peerId, 'video-suspended-poor-network');
  }
}
```

Principles: degrade the *affected* subscriber only (per-consumer layer selection), preserve audio over video (audio is the priority for communication), surface a UI hint ("Your connection is unstable"), and recover automatically when metrics improve. Hysteresis/debouncing prevents oscillation between layers.

### Q44. [Theory] What is end-to-end encryption beyond DTLS-SRTP, and why is it needed for SFUs?

Standard WebRTC DTLS-SRTP encrypts media **hop-by-hop**: the publisher encrypts to the SFU, the SFU **decrypts**, then re-encrypts to each subscriber. That means the SFU sees plaintext media — fine for routing, but the server (or anyone who compromises it) can eavesdrop. For sensitive use cases, that's unacceptable.

**Insertable Streams / SFrame / encoded-transform E2EE** adds a *second* encryption layer the SFU **cannot** read: the publisher encrypts each encoded frame with a key shared only among participants (not the server), and subscribers decrypt with that key. The SFU still routes and reads RTP headers (so it can forward and select layers) but the **payload is opaque** to it.

```
Frame ─▶ [E2EE encrypt, client key]─▶ [DTLS-SRTP] ─▶ SFU (routes, can't read payload)
                                                       └─▶ [DTLS-SRTP] ─▶ subscriber ─▶ [E2EE decrypt]
```

Implemented via `RTCRtpScriptTransform` / encoded-transform in a Worker. Challenges: **key management/distribution** (often via a group key agreement like MLS), key rotation when membership changes, and the SFU losing the ability to do anything needing payload access. This is how privacy-focused products (and features in major conferencing apps) provide E2EE group calls in 2026.

### Q45. [Theory] How do you handle codec negotiation and fallback across heterogeneous devices?

Devices differ in codec support and hardware acceleration. The negotiation has to find a working common codec while preferring efficient, hardware-accelerated ones.

- **SDP advertises capabilities** in preference order; the answerer picks the intersection. You can reorder preferences with `RTCRtpTransceiver.setCodecPreferences()` to favor, say, AV1 → VP9 → H.264 → VP8.
- **Prefer hardware-accelerated codecs** per device: H.264 and increasingly AV1/VP9 have hardware decode on modern hardware → lower CPU and battery. Query support via `RTCRtpReceiver.getCapabilities('video')` / `MediaCapabilities` API.
- **Fallback chain**: if a fancy codec (AV1) isn't supported or hardware-accelerated on a peer, negotiation naturally falls back to a common baseline (VP8/H.264 are near-universal). Always keep a universally supported codec in the offer.
- **In an SFU**, this is trickier: the SFU forwards without transcoding, so **all participants must share a common codec** for a given stream — or the SFU must transcode (expensive) for mismatched subscribers. Many systems pick one codec per room (often VP8/H.264 for compatibility, or VP9/AV1 when all clients support it) to avoid transcoding.

```
setCodecPreferences([AV1, VP9, H264, VP8])  // best → safest
   → answerer intersects → common codec chosen
   → no common modern codec? falls back to VP8/H264 baseline
```

Test the matrix: old Safari, Android hardware quirks, and Firefox all have codec/profile edge cases.

## 🔴 Expert (15+ yrs)

### Q46. [Theory] Design a globally distributed, 100k-viewer interactive live event on WebRTC. Walk through the architecture.

The challenge: a few interactive participants (host, panelists, occasional audience Q&A) plus a massive passive audience, all needing **sub-second** latency and global reach. Pure WebRTC SFU mesh doesn't scale to 100k egress on one tier.

```
        Panelists (full WebRTC, bidirectional)
                 │
            [Origin SFU cluster]  ── records, transcodes layers
              /      |       \
        [Regional SFU/edge relays — cascaded tree, per geo]
          /  |  \      /  |  \      /  |  \
      edge edge edge  ... (CDN-like fanout, WebRTC or WHEP) ...
        │    │    │
     viewers (WHEP/WebRTC subscribe, can be promoted to publisher for Q&A)
```

Key decisions:
- **Tiered fanout (cascading SFUs)**: an origin tier ingests panelists; a tree of regional/edge relays fans out toward viewers, bounding egress per node and putting media close to users (low RTT). Effectively a real-time CDN.
- **Asymmetric roles**: panelists use bidirectional WebRTC; the 100k audience use **WHEP egress** (subscribe-only) — cheaper and standardized. "Raise hand" promotes a viewer to a publisher transport dynamically.
- **Simulcast/SVC + per-edge layer selection** so each viewer gets a layer matched to their device/network.
- **Geo-routing & anycast** to send each viewer to the nearest edge; consistent-hash rooms across SFU shards with a coordination/control plane (etcd/Redis) tracking topology.
- **Graceful degradation tail**: if interactivity isn't needed for the long tail, hand the furthest viewers to **LL-HLS (~2–4 s)** as a cost/scale relief valve, reserving true sub-second WebRTC for engaged users.
- **Capacity & cost**: autoscale edge pools on concurrency, pre-warm before scheduled events, and cap relay (TURN) usage. Recording and transcription run as separate egress workers off the origin.

The art is the trade-off matrix: latency vs. cost vs. scale — keep the interactive core small and real-time, fan the passive majority out cheaply.

### Q47. [Theory] What are the hardest correctness and consistency problems in a large SFU deployment?

Beyond raw scaling, distributed SFUs surface subtle bugs:

- **Keyframe storms**: when many subscribers join or switch layers, each sends a PLI/keyframe request; a naive SFU forwards them all to the publisher, who floods keyframes (huge, congesting everyone). Fix: **coalesce/throttle** keyframe requests and cache the latest keyframe to bootstrap new subscribers.
- **Simulcast layer-switch glitches**: switching a subscriber between layers requires aligning to a keyframe and rewriting RTP sequence numbers/timestamps/picture IDs continuously so the decoder sees one coherent stream. Off-by-one errors cause freezes or artifacts.
- **State across cascaded SFUs**: active-speaker, mute state, and subscription intent must stay consistent across relay nodes. Network partitions between SFU tiers can desync who's speaking or who's subscribed → ghost participants, stuck video.
- **Clock/timestamp handling**: RTP timestamps and RTCP sender reports must be handled correctly for A/V sync and for stitching forwarded streams; cascading adds drift.
- **Reconnection & membership races**: a flapping client (mobile network) repeatedly joining/leaving across shards needs idempotent membership and consistent room ownership (consistent hashing + leases) to avoid split-brain rooms.
- **Backpressure/overload**: one slow subscriber must not stall the forwarding loop for others; per-consumer queues with drop policies are essential.

These are the issues that separate a demo SFU from a production one; most are about **RTP-level rewriting** and **distributed room state**, not the WebRTC handshake.

### Q48. [Theory] Where is WebRTC heading, and what newer standards should an architect track in 2026?

The platform is evolving past the original "media in the browser" framing:

- **WebCodecs + WebTransport**: decouples encoding/decoding (`WebCodecs`) and transport (`WebTransport`, QUIC-based) from the monolithic `RTCPeerConnection`. Lets you build custom low-latency media pipelines (e.g. game streaming, custom congestion control) with full frame access. The trend is "**unbundling**" WebRTC into composable primitives.
- **AV1 + advanced SVC** as the default for new deployments — better compression and clean layer dropping; hardware support is now broad.
- **L4S / ECN congestion control**: explicit congestion signaling for ultra-low-latency, low-loss adaptation, replacing pure loss/delay heuristics.
- **WHIP/WHEP** maturing into the standard ingest/egress for low-latency streaming, blurring the line between WebRTC and CDN streaming.
- **E2EE via encoded-transform + MLS** (Messaging Layer Security) for scalable group key management — making server-blind group calls practical.
- **AI/ML in the media path**: ML-based noise suppression, super-resolution, background effects, and **AI voice agents** joining calls as participants (real-time speech-to-speech). LiveKit and similar platforms are increasingly built around connecting LLMs into live media.
- **RTCRtpScriptTransform** (encoded transform) replacing the older Insertable Streams API for in-pipeline processing.

An architect's job is to know which of these to adopt now (AV1, WHIP/WHEP, encoded-transform E2EE) versus watch (WebTransport-based custom stacks) — and to keep the fallback baseline (UDP, VP8/H.264, TURN) rock solid underneath the new shiny layers.

### Q49. [Behavioral] You're tech lead and must decide: build on a turnkey platform (e.g. LiveKit/Daily/Twilio) or your own SFU stack (mediasoup/Pion). How do you drive that decision?

This tests judgment, not a "right" answer. A strong response frames it as a structured trade-off tied to business context:

- **Clarify requirements first**: scale targets, latency, E2EE/compliance needs, recording, geographic spread, team's media expertise, time-to-market, and budget. The decision falls out of these, not preference.
- **Bias to buy/managed early**: if speed-to-market and a small team matter more than per-minute cost, a managed platform (Twilio/Daily) or open platform (LiveKit Cloud) removes the enormous undifferentiated work of TURN fleets, SFU scaling, global routing, and on-call for media infra. Most products should *not* build an SFU.
- **Build when media is your differentiator or cost dominates**: at large scale, per-minute platform fees can exceed the cost of running your own; or you need custom routing/E2EE/codec behavior the platform won't give you. Then mediasoup/Pion is justified — but staff for it (24/7 media on-call, deep RTP expertise).
- **De-risk with a hybrid/phased path**: ship v1 on a managed platform to validate the product; abstract the media layer behind your own interface so you can migrate hot paths to self-hosted later without rewriting the app. Avoid premature optimization.
- **Decision artifact**: I'd write it up as an explicit trade-off doc (cost model at projected scale, build vs. buy TCO over 18 months, risk/owner-ship, exit options) and align stakeholders — engineering, finance, security — rather than make it a purely technical call.

What this signals: you anchor architecture decisions in business reality, you respect the operational weight of media infrastructure, and you keep optionality. The worst answer is "build our own SFU because it's cool" without the cost/ops reckoning.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q50. [Theory] What actually flows on the wire during a WebRTC session — name the protocol stack layers.

A single WebRTC UDP flow multiplexes several protocols over **one** port (thanks to BUNDLE and the demultiplexer that inspects the first byte of each packet):

```
                 ┌─────────────────────────────────────┐
 Application →   │ media (SRTP)   data (SCTP)   STUN     │
                 ├─────────────────────────────────────┤
 Security    →   │ DTLS (handshake + keys)               │  (SRTP keys derived from DTLS)
                 ├─────────────────────────────────────┤
 Transport   →   │ ICE-managed UDP (or TCP fallback)     │
                 └─────────────────────────────────────┘
```

The receiver demultiplexes by the first byte / value range of each datagram: STUN packets (ICE connectivity checks) have a fixed magic cookie; DTLS records sit in one byte range; SRTP/SRTCP in another. So ICE checks, the DTLS handshake, encrypted media, and the SCTP data channel all coexist on the same 5-tuple. This is why a WebRTC connection looks like "just UDP packets to one IP:port" from the outside — the structure is all inside.

#### Q51. [Theory] What is BUNDLE and what does `a=group:BUNDLE` in the SDP mean?

By default each `m=` line (audio, video, data) could use its own ICE transport and its own port — meaning separate NAT traversal and separate DTLS handshakes per media type. **BUNDLE** (RFC 9143) negotiates running **all** `m=` sections over a **single** ICE/DTLS transport (one 5-tuple). The `a=group:BUNDLE 0 1 2` line lists the `mid` values that share the transport.

```
Without BUNDLE: audio→port A, video→port B, data→port C  (3× ICE + 3× DTLS)
With BUNDLE:    audio+video+data → one port  (1× ICE + 1× DTLS, RTP demux by SSRC/mid)
```

Benefits: one NAT hole to punch (far better connectivity), one DTLS handshake (faster setup), fewer candidates. Different streams are then told apart by SSRC and the `mid`/`rid` RTP header extensions. Modern browsers default to `max-bundle`, and most SFUs require it. The cost is that everything shares one congestion-controlled transport.

#### Q52. [Theory] What is an SSRC and how does it relate to a MediaStreamTrack?

An **SSRC** (Synchronization Source) is a 32-bit random identifier in every RTP packet header that names the *source* of a stream of packets. Each distinct media flow on the wire has its own SSRC — so an audio track and a video track have different SSRCs, and each simulcast layer has its own SSRC too.

```
RTP header: | V | P | ... | sequence# | timestamp | SSRC=0x1A2B3C4D | ...payload... |
```

The browser maps incoming SSRCs to `MediaStreamTrack` objects (using SDP `a=ssrc` lines or, in modern unified-plan/BUNDLE setups, the `mid`/`rid` header extensions). RTCP sender/receiver reports are also keyed by SSRC — that's how A/V sync works: the RTCP Sender Report ties an SSRC's RTP timestamp to a wall-clock (NTP) time so the receiver can align audio and video that arrived on different SSRCs. A single source can also have an associated *retransmission* (RTX) SSRC.

#### Q53. [Theory] Walk through the lifecycle states of an RTCPeerConnection's signalingState.

`signalingState` is the SDP offer/answer state machine (distinct from ICE/connection state). The legal transitions:

```
stable ──setLocalDescription(offer)──▶ have-local-offer ──setRemoteDescription(answer)──▶ stable
stable ──setRemoteDescription(offer)─▶ have-remote-offer ──setLocalDescription(answer)──▶ stable
                                       (provisional answer → have-local/remote-pranswer)
                                       rollback → back to stable
```

- **stable** — no negotiation in progress; the only state in which `negotiationneeded` should fire and the only safe point to start a fresh offer.
- **have-local-offer** — we called `setLocalDescription(offer)`, waiting for the remote answer.
- **have-remote-offer** — we received and set an offer, must now produce an answer.
- **have-local-pranswer / have-remote-pranswer** — provisional answers (rarely used in browser WebRTC).

The key insight for "perfect negotiation": you only initiate a new offer from `stable`, and an incoming offer while not in `stable` is a glare collision. `rollback` returns from `have-local-offer` to `stable`, discarding the local offer.

#### Q54. [Theory] What is a transceiver, and how does it relate to senders and receivers?

An `RTCRtpTransceiver` is the modern (Unified Plan) abstraction pairing **one** `RTCRtpSender` with **one** `RTCRtpReceiver` around a single `m=` line / `mid`. It represents a bidirectional "slot" for one media kind.

```
RTCRtpTransceiver
 ├── sender   (RTCRtpSender)   → outbound: your track, encodings, parameters
 ├── receiver (RTCRtpReceiver) → inbound: remote track
 ├── direction: 'sendrecv' | 'sendonly' | 'recvonly' | 'inactive'
 └── mid: '0'   (matches the SDP m-line)
```

`addTrack()` implicitly creates or reuses a transceiver; `addTransceiver()` creates one explicitly (useful to pre-declare `recvonly` slots or to set `sendEncodings` for simulcast). The `direction` controls whether each `m=` line sends, receives, both, or neither, and changing it triggers renegotiation. Transceivers are also why m-lines are never removed in Unified Plan — they're recycled (set to `inactive`) rather than deleted, keeping m-line ordering stable across renegotiations.

#### Q55. [Theory] What is the difference between Plan B and Unified Plan SDP?

These are two historical ways of representing **multiple** media streams in SDP:

- **Plan B** (Chrome-only legacy): one `m=audio` and one `m=video` line, with *many* sources multiplexed onto each line via multiple `a=ssrc` entries. Compact but non-standard and awkward for per-stream control.
- **Unified Plan** (the standard, RFC 8829/JSEP): **one `m=` line per track/transceiver**. Five video tracks = five `m=video` lines, each with its own `mid` and direction.

```
Plan B:        m=video ... (ssrc 1, ssrc 2, ssrc 3 all here)
Unified Plan:  m=video ... mid:0   (track 1)
               m=video ... mid:1   (track 2)
               m=video ... mid:2   (track 3)
```

Plan B is fully removed from modern browsers; **everything is Unified Plan in 2026**. You only encounter Plan B when interoperating with very old systems or reading historical libwebrtc code. The transceiver API exists precisely because Unified Plan made the 1:1 m-line↔transceiver mapping the model.

#### Q56. [Theory] Why is `getUserMedia` permission "sticky" per origin, and what is a persistent permission?

Browsers bind camera/mic grants to the **origin** (scheme + host + port) under a permission model. After the first grant, a browser may **persist** the decision so subsequent `getUserMedia` calls on that origin resolve without re-prompting (until the user revokes it in site settings). You can inspect this via the Permissions API:

```javascript
const status = await navigator.permissions.query({ name: 'camera' });
// status.state is 'granted' | 'denied' | 'prompt'
```

Important nuances: a grant is for the *origin*, so `https://app.example.com` and `https://other.example.com` are separate; an `<iframe>` needs `allow="camera; microphone"` to delegate the permission via Permissions Policy; and the hardware "in use" indicator (camera LED) is independent of permission — it reflects an *active* track, which is why you `track.stop()` to turn the light off even though the permission remains granted.

#### Q57. [Practical] How do you enumerate devices and let the user pick a specific camera/mic?

`navigator.mediaDevices.enumerateDevices()` lists inputs/outputs. Device **labels are hidden until you have an active permission** (an anti-fingerprinting measure), so call `getUserMedia` once first, then enumerate to show real names.

```javascript
async function listAndPick() {
  // 1. Trigger a permission so labels become visible.
  const probe = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
  probe.getTracks().forEach(t => t.stop()); // release; we just wanted permission

  // 2. Now labels are populated.
  const devices = await navigator.mediaDevices.enumerateDevices();
  const cams = devices.filter(d => d.kind === 'videoinput');
  const mics = devices.filter(d => d.kind === 'audioinput');

  // 3. Open a chosen device by deviceId.
  const stream = await navigator.mediaDevices.getUserMedia({
    video: { deviceId: { exact: cams[0].deviceId } },
    audio: { deviceId: { exact: mics[0].deviceId } }
  });
  return stream;
}

// React to hot-plugging:
navigator.mediaDevices.ondevicechange = () => listAndPick();
```

Use `{ exact: deviceId }` to force a specific device. Listen to `devicechange` so the UI updates when a USB headset is plugged or unplugged.

#### Q58. [Theory] What does the `track` event's `streams` array tell you, and why can it be empty?

When remote media arrives, `ontrack` fires with an `RTCTrackEvent`. The event exposes `event.track` (the `MediaStreamTrack`) and `event.streams` (an array of `MediaStream`s the track belongs to). The streams array is populated from the remote SDP's stream-association signaling (`a=msid`): the sender said "this track is part of stream X," letting the receiver regroup related audio+video into the same `MediaStream`.

```javascript
pc.ontrack = (event) => {
  if (event.streams[0]) {
    remoteVideo.srcObject = event.streams[0];   // grouped audio+video together
  } else {
    // No msid grouping — build your own stream from the lone track.
    remoteVideo.srcObject ??= new MediaStream();
    remoteVideo.srcObject.addTrack(event.track);
  }
};
```

It can be empty when the sender used `addTrack(track)` **without** passing a stream, or with some SFUs that don't relay msid groupings — then you assemble the `MediaStream` yourself by SSRC/mid. Relying on `streams[0]` existing is a common bug against custom servers.

#### Q59. [Theory] What is the secure-context and Permissions-Policy requirement chain for capture APIs?

Capture APIs are gated by several overlapping web-platform mechanisms:

1. **Secure context** — `getUserMedia`, `getDisplayMedia`, and the whole `mediaDevices` interface are only defined in a secure context (HTTPS or `http://localhost`). On plain HTTP `navigator.mediaDevices` is `undefined`.
2. **Permissions Policy** (formerly Feature Policy) — the document must be allowed `camera`, `microphone`, `display-capture`. A cross-origin `<iframe>` is denied by default and needs an explicit `allow="camera; microphone"` attribute, and the top document can restrict it via a `Permissions-Policy` header.
3. **Transient activation** — `getDisplayMedia` (screen share) additionally requires a **user gesture** (a click); it will reject if called without one.

```
HTTPS/localhost ── secure context ──▶ mediaDevices exists
        + Permissions-Policy allows camera/mic ──▶ getUserMedia may prompt
        + user gesture (for screen share) ──────▶ getDisplayMedia may prompt
```

Getting "Permission denied" with no prompt is often #1 or #2, not the user clicking "deny." Always test capture over HTTPS, and check the iframe `allow` attribute when capture silently fails only inside an embed.

#### Q60. [Theory] What is the difference between `enabled`, `muted`, and `readyState` on a MediaStreamTrack?

Three distinct properties people conflate:

- **`enabled`** (read/write, app-controlled): your mute switch. `false` makes the track emit silence/black but keeps it flowing. This is the only one you set.
- **`muted`** (read-only, source-controlled): reflects whether the *source* is temporarily not producing data — e.g. the OS grabbed the camera, the user is on a phone call, or the SFU paused it. You observe it via `onmute`/`onunmute`; you cannot set it.
- **`readyState`** (read-only): `'live'` while the source can produce frames, `'ended'` once the source is permanently gone (device unplugged, or you called `stop()`). `onended` fires on the transition.

```
enabled=false  → app muted (you did it),    track still live
muted=true     → source dry (OS/network),   transient, not your doing
readyState=ended → source gone for good,    needs a fresh getUserMedia
```

A frozen remote video with `muted=true` usually means the network/SFU starved it, not that the user clicked mute — different diagnosis entirely.

### 🟡 — extended

#### Q61. [Theory] Explain the DTLS handshake roles: what does `a=setup:actpass` / `active` / `passive` mean?

DTLS needs one side to be the client (sends `ClientHello`, initiates) and one the server (`passive`). WebRTC negotiates these roles in SDP via the `a=setup` attribute:

- **`actpass`** — "I can be either; you decide." The **offerer** always sends `actpass`.
- **`active`** — "I'll be the DTLS client (I initiate the handshake)."
- **`passive`** — "I'll be the DTLS server (I wait)."

```
Offer:  a=setup:actpass   (offerer is flexible)
Answer: a=setup:active    (answerer chose to initiate) ──▶ answerer sends ClientHello
        (or a=setup:passive, then the offerer initiates)
```

Conventionally the answerer picks `active` and initiates, so the DTLS handshake starts as soon as ICE connects — no extra round trip waiting for the offerer. This matters for **ICE restart and reconnection**: getting the setup role wrong (both `active` or both `passive`) deadlocks the handshake, a classic bug in hand-rolled SDP munging. The DTLS role also determines the SRTP key derivation direction.

#### Q62. [Theory] How are SRTP keys actually derived, and what is DTLS-SRTP key extraction?

The DTLS handshake doesn't just authenticate — it produces a shared master secret, and SRTP keys are **extracted** from it rather than sent separately (this is "DTLS-SRTP," RFC 5764). After the handshake:

```
DTLS master secret
   │  (RFC 5705 keying material exporter, label "EXTRACTOR-dtls_srtp")
   ▼
keying material → split into:
   client_write_SRTP_master_key + salt
   server_write_SRTP_master_key + salt
   │  (SRTP KDF per RFC 3711)
   ▼
per-packet SRTP session keys (encryption + auth)
```

Crucially, the keys are never transmitted in the SDP or anywhere — only the **certificate fingerprint** is in the SDP. Authentication works because the DTLS certificate presented during the handshake must match the `a=fingerprint` carried over the (trusted, TLS-protected) signaling channel. So the security guarantee is: *if your signaling channel is secure, the media is secure*, because an attacker who can't tamper with the fingerprint can't substitute a certificate. The SRTP profile (e.g. `AES_CM_128_HMAC_SHA1_80` or AES-GCM) is negotiated via `a=crypto`-equivalent DTLS extension.

#### Q63. [Theory] What is the ICE candidate priority formula and why does it favor host over relay?

ICE assigns each candidate a 32-bit priority and pairs are ordered by combining both sides' priorities. The per-candidate formula (RFC 8445) is:

```
priority = (2^24) * type_preference
         + (2^8)  * local_preference
         + (2^0)  * (256 - component_id)
```

`type_preference` is the dominant term (multiplied by 2^24), with recommended values: **host = 126**, peer-reflexive = 110, **server-reflexive (STUN) = 100**, **relay (TURN) = 0**. So a host candidate always outranks an srflx, which always outranks a relay — encoding the preference "use the most direct path that works." `local_preference` breaks ties between a peer's own candidates (e.g. prefer Wi-Fi over cellular, or IPv6 over IPv4). The candidate-**pair** priority then combines controlling/controlled priorities so both peers compute the same checklist order. This is why media defaults to the LAN path on the same network and only relays when nothing direct connects.

#### Q64. [Coding] Parse an ICE candidate string and identify its type and transport.

A candidate line is a structured string. Knowing its fields lets you log which path won and detect relay-only situations.

```javascript
function parseCandidate(candidateStr) {
  // Example: "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host generation 0"
  const p = candidateStr.replace(/^candidate:/, '').split(' ');
  const fields = {
    foundation: p[0],
    component: p[1] === '1' ? 'RTP' : 'RTCP',
    transport: p[2],            // udp | tcp
    priority: Number(p[3]),
    ip: p[4],
    port: Number(p[5]),
  };
  const typIdx = p.indexOf('typ');
  fields.type = p[typIdx + 1];  // host | srflx | prflx | relay
  // For srflx/relay, related address shows the base/mapped address:
  const rIdx = p.indexOf('raddr');
  if (rIdx !== -1) { fields.relatedAddress = p[rIdx + 1]; fields.relatedPort = Number(p[rIdx + 3]); }
  return fields;
}

pc.onicecandidate = ({ candidate }) => {
  if (!candidate) { console.log('gathering complete'); return; }
  const c = parseCandidate(candidate.candidate);
  console.log(`${c.type} ${c.transport} ${c.ip}:${c.port}`);
  // If you only ever see typ relay, direct/STUN paths failed → diagnose firewall/NAT.
};
```

Watching the `type` distribution across users is the cheapest way to spot networks that always fall back to TURN.

#### Q65. [Theory] What are aggressive vs. regular nomination in ICE, and what is the controlling/controlled role?

In ICE, one agent is **controlling** and the other **controlled** — decided by a tie-breaker random value exchanged in STUN checks (the agent that generated the offer is controlling in the full-ICE/WebRTC case). The controlling agent decides which working candidate pair becomes the **nominated** (selected) pair for media:

- **Regular nomination**: the controlling agent first runs connectivity checks, then sends a second check with the `USE-CANDIDATE` flag on the pair it chooses — a deliberate two-phase selection.
- **Aggressive nomination**: the controlling agent sets `USE-CANDIDATE` on the *very first* check of every pair, so the first pair to succeed is immediately nominated — faster but less controlled.

```
Controlling agent → sends USE-CANDIDATE → pins the selected pair
Controlled agent  → accepts whatever the controlling side nominates
```

WebRTC's modern ICE largely uses regular nomination with continuous checking. The role also matters for **glare in ICE restarts** and for the `ice-lite` case (servers that only respond, never initiate checks — common for SFUs, which advertise `a=ice-lite`).

#### Q66. [Theory] What is `ice-lite` and why do SFUs/media servers use it?

`a=ice-lite` (in SDP) declares that an endpoint runs a **minimal ICE** implementation: it has only **host candidates** (it's a public server with a routable IP), it never gathers srflx/relay candidates, and it never initiates connectivity checks — it only **responds** to the peer's checks.

```
Full ICE client (behind NAT): gathers host+srflx+relay, sends checks
ice-lite server (public IP):   one host candidate, just answers checks
```

SFUs and TURN-adjacent media servers use ice-lite because they already sit on a public IP with no NAT to traverse — gathering reflexive/relay candidates would be pointless overhead. It also implies the **client is always the controlling agent** (the lite side is always controlled), which simplifies the server. The trade-off: an ice-lite server can't traverse NAT itself, so it must be publicly reachable — fine for cloud-hosted media servers, wrong for a peer behind a home router.

#### Q67. [Theory] How does RTCP work alongside RTP, and what are RR/SR/REMB/TWCC feedback?

RTP carries media; **RTCP** (RTP Control Protocol) carries out-of-band control/feedback on a parallel flow (or RTCP-muxed onto the same port via `a=rtcp-mux`). Key RTCP packet types:

- **SR (Sender Report)**: sender → receivers. Maps the stream's RTP timestamp to NTP wall-clock (enables A/V sync) and reports packets/bytes sent.
- **RR (Receiver Report)**: receiver → sender. Reports fraction lost, cumulative lost, highest sequence number, jitter, and round-trip delay.
- **NACK / PLI / FIR**: loss/keyframe feedback (covered earlier).
- **REMB** (Receiver Estimated Max Bitrate): older bandwidth-estimation feedback — receiver tells sender "you may send up to X bps."
- **TWCC** (Transport-Wide Congestion Control): the modern scheme — the receiver reports **per-packet arrival times** for *all* packets across the transport, letting the **sender** compute delay gradients and run congestion control centrally.

```
RTP →  media packets
RTCP ← RR (loss/jitter) + TWCC (arrival times) + NACK (resend pkt N) + PLI (need keyframe)
```

The shift REMB→TWCC moved the bandwidth-estimate computation from receiver to sender, giving the encoder side full, precise feedback — the foundation of GCC.

#### Q68. [Theory] What is `a=rtcp-mux` and `a=rtcp-rsize`, and why do they matter?

Historically RTP and RTCP used **two ports** per media stream (RTP on an even port, RTCP on the next odd port). That doubles the NAT holes to punch and the candidates to gather.

- **`a=rtcp-mux`** (RFC 5761): multiplex RTCP onto the **same** port as RTP, demultiplexed by RTP payload-type ranges. Halves the ports/candidates and is effectively mandatory in modern WebRTC (combined with BUNDLE, the whole session is one port).
- **`a=rtcp-rsize`** (reduced-size RTCP, RFC 5506): allows RTCP packets that **omit** the mandatory Sender/Receiver Report prefix, so a tiny feedback message (like a single NACK) isn't padded with a full report. Reduces feedback overhead, which matters at high feedback rates (TWCC sends frequent reports).

```
Old:  RTP:port 5000   RTCP:port 5001     (2 holes per stream)
New:  rtcp-mux  → RTP+RTCP on one port    + rtcp-rsize → lean feedback packets
```

Together with BUNDLE these reduce a multi-stream session to a single ICE transport with minimal feedback overhead — essential for connectivity through restrictive NATs and for scaling feedback at the SFU.

#### Q69. [Theory] What exactly is the data channel's transport — explain SCTP-over-DTLS-over-UDP and its reliability knobs.

`RTCDataChannel` rides on **SCTP** (Stream Control Transmission Protocol) encapsulated in **DTLS** over **UDP** (RFC 8831). SCTP is what gives data channels their configurable semantics that neither raw UDP nor TCP offer:

```
RTCDataChannel  → SCTP (multi-stream, configurable reliability/ordering)
                   → DTLS (encryption, RFC 8261 encapsulation)
                     → UDP (or TCP/TURN fallback)
```

SCTP provides **multiple independent streams** within one association (so head-of-line blocking is per-channel, not global), plus **partial reliability** (PR-SCTP):

- **Reliable + ordered** (default): like TCP per channel.
- **`maxRetransmits: 0`**: unreliable — never resend (fire-and-forget).
- **`maxRetransmits: n`**: try up to n resends, then give up.
- **`maxPacketLifeTime: ms`**: resend only within a time budget, then give up.
- **`ordered: false`**: deliver as received, no reordering wait.

Set exactly one of `maxRetransmits`/`maxPacketLifeTime`. Flow control uses SCTP's receive window; `bufferedAmount` + `bufferedAmountLowThreshold` let you backpressure large sends so you don't blow up memory.

#### Q70. [Coding] Implement backpressure when sending a large file over a data channel.

A data channel buffers in memory; sending faster than the network drains causes `bufferedAmount` to balloon and can crash the tab. Use `bufferedAmountLowThreshold` and the `bufferedamountlow` event to pace.

```javascript
async function sendFile(channel, file) {
  const CHUNK = 16 * 1024;                 // 16 KiB chunks (safe SCTP message size)
  const HIGH_WATER = 8 * 1024 * 1024;      // pause when 8 MiB is queued
  channel.bufferedAmountLowThreshold = 1 * 1024 * 1024; // resume at 1 MiB

  const reader = file.stream().getReader();
  let leftover = new Uint8Array(0);

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    let data = concat(leftover, value);
    let offset = 0;
    while (offset + CHUNK <= data.length) {
      // Backpressure: if the send buffer is too full, wait for it to drain.
      if (channel.bufferedAmount > HIGH_WATER) {
        await new Promise(res => {
          channel.onbufferedamountlow = () => { channel.onbufferedamountlow = null; res(); };
        });
      }
      channel.send(data.subarray(offset, offset + CHUNK));
      offset += CHUNK;
    }
    leftover = data.subarray(offset);
  }
  if (leftover.length) channel.send(leftover);
}

function concat(a, b) { const c = new Uint8Array(a.length + b.length); c.set(a); c.set(b, a.length); return c; }
```

Without backpressure, `send()` never blocks — it just queues — so a fast loop over a large file silently exhausts memory. Keep chunks ≤ ~16 KiB to stay under SCTP message-size limits across implementations.

#### Q71. [Theory] How does WebRTC achieve audio/video synchronization (lip sync) across separate streams?

Audio and video travel as **separate RTP streams with independent SSRCs, clocks, and timestamps** — yet must play in sync. The mechanism is RTCP **Sender Reports**:

```
Each SR maps:  this stream's RTP timestamp  ⇄  a common NTP wall-clock time
Audio SR:  RTP ts 48000 ↔ NTP T
Video SR:  RTP ts 90000 ↔ NTP T'
```

The receiver uses these mappings to translate each stream's media-clock timestamps into a **shared wall-clock timeline**, then schedules playout so that audio and video frames captured at the same real instant are rendered together. The jitter buffer depth on each stream is adjusted so the *later*-arriving medium waits for the other (usually video waits a bit for audio, since audio desync is more perceptible). RTP timestamps use per-media clock rates (48 kHz for Opus, 90 kHz for video), and the SR's NTP↔RTP pair is the Rosetta Stone tying them. Broken or missing Sender Reports (a common SFU forwarding bug) is the usual cause of drifting lip sync.

#### Q72. [Theory] What is the difference between `RTCRtpSender.getParameters()/setParameters()` and SDP munging for bitrate control?

To cap or shape outbound bitrate you have two routes:

- **SDP munging** (old/fragile): edit the SDP string before `setLocalDescription`, e.g. add `b=AS:500` or `b=TIAS:` to an m-line. It's brittle (string surgery on browser-generated SDP), applies coarsely per m-line, and breaks across browser versions. Avoid when possible.
- **`setParameters()`** (modern/recommended): mutate the live encoding parameters via the sender API — no renegotiation, per-encoding (per simulcast layer) control:

```javascript
const sender = pc.getSenders().find(s => s.track?.kind === 'video');
const params = sender.getParameters();
params.encodings[0].maxBitrate = 500_000;          // 500 kbps cap
params.encodings[0].maxFramerate = 24;
params.encodings[0].scaleResolutionDownBy = 2;     // halve resolution
params.encodings[0].active = true;                 // or false to pause this layer
await sender.setParameters(params);                // applies immediately, no SDP exchange
```

`setParameters` is the correct tool for runtime adaptation (e.g. a "low data mode" toggle, or pausing the top simulcast layer when uplink is constrained). You must read `getParameters()` first and mutate that object — you can't construct one from scratch.

### 🟠 — extended

#### Q73. [Theory] Explain how an SFU rewrites RTP when switching a subscriber between simulcast layers.

When the SFU forwards a subscriber from, say, the 360p layer to the 720p layer, the two source layers have **independent SSRCs, sequence number spaces, timestamp bases, and picture IDs**. If the SFU just started forwarding the new layer's raw packets, the subscriber's decoder would see a sequence-number/timestamp discontinuity and break. So the SFU maintains a **continuous, rewritten output stream** per subscriber:

```
Layer A (360p): seq …100,101,102   ts …9000,9090   picId …5,6,7
Layer B (720p): seq …700,701       ts …4000,4090   picId …40,41
                          │  switch at a keyframe
SFU output to sub:  seq …103,104   ts …9180,9270   picId …8,9   (monotonic, rewritten)
```

The SFU: (1) waits for or requests a **keyframe** on the target layer (you can only switch at a decodable boundary), (2) rewrites sequence numbers to stay monotonic, (3) offsets timestamps onto one continuous timeline, and (4) rewrites codec-specific picture IDs / temporal indices (VP8/VP9 `picID`, AV1 dependency descriptor). Bugs here cause the freezes and artifacts that distinguish a hobby SFU from a production one.

#### Q74. [Theory] What is the AV1 Dependency Descriptor / VP9 SS, and why does an SFU need it?

To drop SVC layers correctly, an SFU must know **which packets belong to which spatial/temporal layer and what each frame depends on** — without decoding the payload. Codecs expose this via RTP header extensions / payload structures:

- **VP8**: temporal-layer index in the payload descriptor (`TID`).
- **VP9**: the **Scalability Structure (SS)** and per-packet layer indices describe spatial/temporal layers and inter-layer dependencies.
- **AV1**: the **Dependency Descriptor (DD)** RTP header extension — a compact, codec-agnostic-ish description of each frame's spatial/temporal IDs and its dependencies on other frames.

```
DD/SS tells the SFU:  frame F is spatial=1 temporal=2, depends on frames {X,Y}
   → SFU can drop temporal=2 frames for a constrained subscriber, knowing
     base layers remain self-decodable.
```

With this metadata the SFU can **selectively forward** a subset of layers per subscriber and guarantee the forwarded subset is still decodable. Without it, the SFU is blind to layer structure and can only do whole-stream forwarding (no SVC adaptation). This is why AV1's well-specified DD is a major reason AV1+SVC is favored for modern SFUs.

#### Q75. [Theory] How do you keep A/V sync and avoid keyframe storms when many subscribers join a popular stream simultaneously?

Two related stampede problems at scale:

- **Keyframe storm**: each new subscriber (or layer switch) needs a keyframe to start decoding, so each sends a PLI/FIR upstream. A flash crowd → thousands of PLIs → the publisher encodes a flood of large keyframes → congestion for everyone. Mitigations: **coalesce** PLIs (collapse many requests in a window into one), **rate-limit** keyframe requests to the publisher, and **cache the most recent keyframe + subsequent packets** at the SFU so a new subscriber is bootstrapped from cache without disturbing the publisher.

```
1000 joiners → 1000 PLIs ──(naive)──▶ publisher emits 1000 keyframes 💥
1000 joiners → SFU serves cached keyframe to each, sends ≤1 PLI upstream ✅
```

- **Sync on join**: a late joiner must align audio and video from the cached/forwarded point. The SFU forwards the cached keyframe plus the RTCP Sender Reports so the new subscriber can establish the NTP↔RTP mapping for lip sync. Combined with per-subscriber sequence/timestamp rewriting, the joiner sees a clean, synced stream starting at a keyframe — without a thundering herd hitting the origin encoder.

#### Q76. [Theory] What is the difference between transport-cc (TWCC) feedback at the sender vs. receiver, and how does GCC use it?

GCC (Google Congestion Control) can run in two configurations; modern WebRTC uses the **sender-side** variant driven by TWCC:

```
Receiver: timestamps every arriving packet → packs arrival times into TWCC feedback (RTCP)
Sender:   receives TWCC → reconstructs per-packet (send_time, arrival_time) pairs
          → computes inter-arrival delay gradient (delay-based estimator, a Kalman/trendline filter)
          → AIMD on the delay signal, plus a loss-based estimator as a floor
          → outputs target bitrate → feeds encoder + pacer
```

The **delay-based** estimator detects a building queue *before* loss occurs by watching whether packets are arriving with growing inter-arrival spacing relative to how they were sent — a positive delay gradient means congestion onset, so it backs off proactively. The **loss-based** estimator is a secondary safety net (back off on >10% loss, ramp on <2%). A **pacer** then smooths packet emission to the target rate so bursts don't self-induce queueing. Putting the computation at the sender (via TWCC arrival reports) gives the estimator and the encoder a single coherent control loop — the receiver just timestamps and reports.

#### Q77. [Coding] Compute packet loss rate and jitter trend from successive getStats() snapshots.

Single `getStats()` values are cumulative; meaningful signals come from **deltas between snapshots**.

```javascript
let prev = null;
async function sampleQuality(pc) {
  const stats = await pc.getStats();
  let inbound;
  stats.forEach(r => { if (r.type === 'inbound-rtp' && r.kind === 'video') inbound = r; });
  if (!inbound) return null;

  const now = {
    t: inbound.timestamp,
    received: inbound.packetsReceived,
    lost: inbound.packetsLost,
    jitter: inbound.jitter,            // seconds, already smoothed
    bytes: inbound.bytesReceived,
  };

  let result = { jitterMs: now.jitter * 1000 };
  if (prev) {
    const dReceived = now.received - prev.received;
    const dLost = now.lost - prev.lost;
    const dBytes = now.bytes - prev.bytes;
    const dt = (now.t - prev.t) / 1000;            // seconds
    result.lossRate = dLost / Math.max(1, dReceived + dLost);   // fraction in this interval
    result.bitrateKbps = (dBytes * 8) / 1000 / dt;
  }
  prev = now;
  return result;          // e.g. { jitterMs: 18, lossRate: 0.03, bitrateKbps: 1420 }
}

// Poll every ~2s; alert if lossRate > 0.05 sustained or bitrate collapses.
setInterval(() => sampleQuality(pc).then(q => q && console.log(q)), 2000);
```

Interval loss rate (not lifetime average) is what reflects *current* conditions; lifetime numbers hide a fresh spike.

#### Q78. [Theory] How does an SFU cascade preserve congestion control and bandwidth estimation end to end?

In a cascaded tree (origin → edge → subscriber), congestion control can't be a single end-to-end loop because each hop is an independent transport. Instead each **leg runs its own BWE**, and the SFU mediates:

```
Publisher ──GCC leg 1──▶ Origin SFU ──GCC leg 2──▶ Edge SFU ──GCC leg 3──▶ Subscriber
   uplink BWE             relay decides           relay decides            downlink BWE
                          which layers to          which layers to
                          forward downstream        forward to sub
```

Each edge runs sender-side congestion control toward its downstream peer and chooses the simulcast/SVC layer that fits that leg's estimate — so a congested *edge→subscriber* leg degrades only that subtree, while the *origin→edge* leg may still carry full quality for other regions. The SFU does **not** transcode; it adapts purely by **layer selection** per leg. The hard parts: avoiding feedback amplification (each leg generating PLIs upstream — coalesce them), keeping timestamps coherent across rewrites at each hop, and ensuring the per-leg estimates don't oscillate. This per-leg decoupling is exactly why cascading scales: failures and congestion stay local to a subtree.

#### Q79. [Theory] What is RED and how does audio redundancy (RFC 2198) improve resilience?

**RED** (REDundant coding, RFC 2198) lets a sender pack **previous audio frames alongside the current one** in a single RTP packet, so the loss of one packet doesn't lose that audio — the next packet still carries a copy.

```
Without RED:  pkt N = frame N          (lose pkt N → frame N gone)
With RED:     pkt N = frame N + frame N-1 (lose pkt N → frame N-1 still arrived in pkt N-1 too,
                                           and frame N may be recoverable from pkt N+1's copy)
```

This is distinct from Opus **in-band FEC** (which embeds a low-bitrate copy of the *prior* frame inside the current Opus payload, decoded only if the previous packet was lost). RED is codec-agnostic packet-level redundancy and can stack multiple generations; Opus FEC is codec-integrated and cheaper. Both trade bandwidth for resilience with **zero added latency** (no retransmit round trip), which is why audio — where even brief gaps are very noticeable and there's no time to NACK — leans on FEC/RED while video leans on NACK + occasional keyframes. Many deployments enable Opus FEC plus RED for two layers of protection on lossy networks.

#### Q80. [Theory] What is `playoutDelayHint` / how can apps tune the latency-vs-smoothness trade-off of the jitter buffer?

The jitter buffer adapts automatically, but apps sometimes need to bias it. WebRTC exposes a hint on the receiver:

```javascript
const receiver = pc.getReceivers().find(r => r.track.kind === 'video');
receiver.playoutDelayHint = 0;     // bias toward minimum latency (interactive)
// or
receiver.playoutDelayHint = 0.4;   // 400 ms — bias toward smoothness (one-way viewing)
```

- **Interactive use (calls, cloud gaming)**: a low/zero hint tells the buffer to keep depth minimal, accepting occasional concealment to stay conversational.
- **One-way viewing (a webinar a viewer just watches)**: a larger hint lets the buffer absorb more jitter for glassy-smooth playback, since a few hundred ms of added latency doesn't hurt a passive viewer.

The hint is advisory — the implementation still grows the buffer if jitter demands it (it can't conjure packets that haven't arrived). This is the per-stream knob behind "the SFU can offer a low-latency interactive mode and a smooth viewing mode from the same media," and it pairs with the `playout-delay` RTP header extension so a server can signal a desired delay too.

#### Q81. [Theory] How does WebRTC interoperate with SIP/PSTN, and what is the role of a gateway/B2BUA?

WebRTC and traditional telephony (SIP/PSTN) differ on signaling, transport, and media, so a **gateway** must translate at every layer:

```
WebRTC side                    Gateway (B2BUA / SBC)                SIP/PSTN side
 SDP over WebSocket/WHIP   ⇄   signaling translation           ⇄   SIP over UDP/TCP/TLS
 ICE/DTLS-SRTP             ⇄   media plane: decrypt SRTP,           RTP (often unencrypted) / SRTP
                               transcode Opus ⇄ G.711, transrate
 Trickle ICE, BUNDLE       ⇄   bridge to plain RTP/RTCP             plain RTP, separate ports
```

A **B2BUA** (Back-to-Back User Agent) or **SBC** (Session Border Controller) terminates the WebRTC session and originates a SIP session, mapping call control (offer/answer ⇄ SIP INVITE/200/ACK), and an **MGW** (media gateway) bridges media: often **transcoding Opus↔G.711**, converting DTLS-SRTP↔RTP, and reconciling that PSTN uses 8 kHz narrowband. DTMF tones map via RFC 4733 telephone-event. This is exactly the heritage Janus and similar gateways were built for, and it's why "dial-in to a meeting by phone" features exist — a media server transcodes the PSTN leg into the SFU's WebRTC world.

#### Q82. [Theory] What is the `MediaStreamTrack` "content hint" and how does it affect encoding?

`track.contentHint` tells the encoder what *kind* of content a track carries so it can tune its rate-control and degradation strategy. The encoder otherwise has to guess from motion.

```javascript
videoTrack.contentHint = 'motion';  // camera/sports → prioritize frame rate, accept softer detail
videoTrack.contentHint = 'detail';  // screen share of text → prioritize sharpness, drop frame rate
videoTrack.contentHint = 'text';    // even stronger bias to crisp text, very low motion
audioTrack.contentHint = 'music';   // disable speech-tuned processing (no DTX/aggressive NS)
audioTrack.contentHint = 'speech';  // enable speech optimizations
```

This drives the **degradation preference**: under bandwidth pressure a `'motion'` track drops resolution to keep frame rate smooth, while a `'detail'`/`'text'` track drops frame rate to keep each frame sharp (you'd rather read crisp slides at 5 fps than blurry slides at 30 fps). For audio, `'music'` disables speech-oriented DSP (DTX, aggressive noise suppression) that would mangle music. Setting the hint on a screen-share track is a cheap, high-impact quality win that many apps forget.

### 🔴 — extended

#### Q83. [Theory] Explain end-to-end how `RTCRtpScriptTransform` (encoded transform) enables E2EE without the SFU reading payloads.

`RTCRtpScriptTransform` (the standardized successor to Insertable Streams) inserts a **Worker-based transform** into the encode/decode pipeline that operates on **encoded frames** (`RTCEncodedVideoFrame`/`RTCEncodedAudioFrame`) — *after* the encoder, *before* packetization on send; the mirror on receive.

```
Sender:   encoder → [transform: E2EE-encrypt payload with group key] → packetize → DTLS-SRTP → SFU
SFU:      routes by RTP header (readable) — payload is opaque ciphertext
Receiver: DTLS-SRTP → depacketize → [transform: E2EE-decrypt with group key] → decoder
```

```javascript
// main thread
const sender = pc.getSenders()[0];
sender.transform = new RTCRtpScriptTransform(worker, { operation: 'encrypt' });

// worker.js
self.onrtctransform = (event) => {
  const { readable, writable } = event.transformer;
  readable.pipeThrough(new TransformStream({
    transform(frame, controller) {
      const data = new Uint8Array(frame.data);
      encryptInPlaceLeavingCodecHeader(data, groupKey);  // keep first N bytes readable
      frame.data = data.buffer;
      controller.enqueue(frame);
    }
  })).pipeTo(writable);
};
```

Crucially you must **leave the codec's leading bytes / metadata unencrypted** (e.g. the bytes the SFU/DD needs for layer selection) and encrypt only the rest of the payload, so the SFU can still route and select layers while never seeing plaintext pixels. The SFU reads RTP headers (SSRC, seq, marker, header extensions) but the media payload is end-to-end ciphertext.

#### Q84. [Theory] What is SFrame and how does it differ from per-sender custom encryption for E2EE?

**SFrame** (Secure Frames, IETF) is a *standardized* framing for end-to-end media encryption designed specifically for SFU forwarding. Rather than each app inventing its own encrypted-frame format, SFrame defines:

- A uniform **encrypted frame** layout: a small SFrame header (key id `KID` + monotonic counter `CTR`) followed by AEAD-encrypted media, with an authentication tag.
- AEAD (e.g. AES-GCM / AES-CTR+HMAC) over the media payload, with the counter ensuring unique nonces.
- A clean separation so the **SFU sees only the SFrame header and the underlying RTP header**, never the plaintext.

```
SFrame frame:  [ SFrame hdr: KID | CTR ] [ AEAD-encrypted media ] [ auth tag ]
               └ SFU may read for routing ┘ └────── opaque to SFU ──────────┘
```

Versus a hand-rolled scheme, SFrame gives **interoperability** (different clients/SDKs agree on the format), a vetted nonce/counter construction (avoiding catastrophic nonce reuse), and a defined relationship with key management. SFrame handles the *bulk media* encryption; it's deliberately decoupled from **key distribution**, which is where MLS comes in (next question). Browsers expose SFrame-style E2EE via encoded-transform; some platforms ship a built-in SFrame transform.

#### Q85. [Theory] Why is MLS (Messaging Layer Security) the emerging answer to group key management for E2EE calls?

E2EE media needs a **shared symmetric key among exactly the current participants**, with **forward secrecy** and **post-compromise security**, and efficient **rekeying** as people join/leave a large call. Pairwise key exchange (Signal-style Double Ratchet) is O(N²) per group and doesn't scale to large meetings. **MLS** (RFC 9420) solves this:

- A **ratchet tree** structure makes group operations **O(log N)** — adding/removing a member and deriving a new group secret touches only a logarithmic number of nodes.
- **Forward secrecy + post-compromise security**: each membership change ("epoch") rotates keys so a leaver can't decrypt future media and a past compromise heals.
- A defined **add/remove/update** protocol so membership changes are cryptographically agreed.

```
Join/leave  → MLS epoch change → new group secret (O(log N) work)
                              → derive SFrame media key for the new epoch
                              → SFU forwards opaque frames; only members hold the key
```

In a WebRTC E2EE call, MLS distributes/rotates the **group key**, SFrame (or encoded-transform) uses that key to encrypt media frames, and the SFU routes ciphertext blindly. This MLS + SFrame + encoded-transform stack is the 2026 blueprint for *scalable*, server-blind group calls — the part that was historically the blocker (efficient group rekeying) now has a standard.

#### Q86. [Theory] What does "unbundling WebRTC" into WebTransport + WebCodecs mean, and when would you choose it over RTCPeerConnection?

The monolithic `RTCPeerConnection` bundles capture, encode, congestion control, packetization, NAT traversal, and transport. The newer primitives **decompose** this:

- **WebCodecs** — direct access to the platform's **encoders/decoders** at the frame level (`VideoEncoder`/`VideoDecoder`, `AudioEncoder`/`AudioDecoder`), yielding `EncodedVideoChunk`s. You control codec, bitrate, keyframe cadence, and you see raw frames.
- **WebTransport** — a **QUIC-based** bidirectional transport (datagrams + reliable streams) to a server, with your own framing and, if you want, your own congestion control.

```
RTCPeerConnection: one black box (encode+BWE+packetize+ICE+SRTP), great defaults
Unbundled:  getUserMedia → WebCodecs encode → your packetizer → WebTransport (QUIC) → server
            (you own pacing, FEC, retransmit policy, jitter handling)
```

Choose unbundled when you need **control RTCPeerConnection won't give you**: custom congestion control, non-standard media formats, tight integration with a game engine, server-authoritative media routing without SDP, or experimentation with new transport behavior. The cost is that you **reimplement** what WebRTC gives free — NAT traversal (WebTransport is client↔server, no P2P/ICE), jitter buffering, congestion control, A/V sync, loss recovery. So it's powerful for client↔server low-latency pipelines (cloud gaming, custom streaming) but you don't get P2P or the battle-tested media stack. Most conferencing still wants `RTCPeerConnection`; the unbundled stack is for bespoke pipelines.

#### Q87. [Theory] How do RTP header extensions like abs-send-time, transport-sequence-number, and mid/rid actually enable SFU features?

Several SFU capabilities depend on **RTP header extensions** (RFC 8285, one/two-byte extension headers) that carry metadata the SFU reads *without* touching the payload:

- **`transport-sequence-number`** (TWCC): a transport-wide counter the receiver echoes in TWCC feedback → enables sender-side congestion control across the whole transport.
- **`abs-send-time`**: the sender's absolute send timestamp → lets the receiver/SFU estimate inter-arrival delay (used by REMB-era and as a BWE input).
- **`mid`** and **`rid`**: identify which transceiver (`mid`) and which simulcast layer (`rid`) a packet belongs to → the SFU uses these to map packets to streams/layers and to do simulcast selection *without* relying on SSRC tables.
- **`audio-level`** (RFC 6464): the sender tags each audio packet with its volume → the SFU ranks **active speakers** by reading the header, no decoding needed.
- **`abs-capture-time`**: capture timestamp for cross-stream sync.
- **`playout-delay`**: signals desired jitter-buffer behavior.

```
SFU reads header extensions to: pick active speaker (audio-level),
  select simulcast layer (rid), run BWE (transport-seq, abs-send-time),
  forward + rewrite — all WITHOUT decoding or, with E2EE, even reading the payload.
```

This is the architectural key to SFUs and to E2EE coexisting: **everything the SFU needs lives in RTP headers/extensions**, so the payload can be opaque ciphertext while routing, congestion control, and speaker detection still work.

#### Q88. [Theory] What is L4S / ECN and how does it change WebRTC congestion control versus pure GCC?

**L4S** (Low Latency, Low Loss, Scalable throughput) uses **ECN** (Explicit Congestion Notification) bits in the IP header so that **routers mark packets** when their queue starts building, instead of dropping them. The sender reacts to the *mark* rather than to loss or inferred delay.

```
Classic GCC:   infer congestion from delay gradient + loss (reactive, noisy)
L4S/ECN:       router sets ECN-CE mark at queue onset → receiver echoes mark in feedback
               → sender backs off immediately, before queue grows or loss occurs
```

Advantages over pure GCC: the signal is **explicit and early** (the network tells you, you don't infer it), so queues stay tiny → consistently **low latency under load**, and there's **little to no packet loss** because routers mark instead of drop. WebRTC integrates this by having the receiver report ECN marks (extending TWCC-style feedback) and the sender's controller treat a mark like a gentle "reduce now." In 2026 L4S is rolling out where the network path (ISPs, data-center fabrics) supports ECN-marking AQM; WebRTC keeps GCC as the fallback when ECN signals aren't available, so it degrades gracefully on non-L4S paths. The net effect is tighter, lower-latency adaptation for real-time media on supporting networks.

#### Q89. [Practical] How would you design observability for a fleet of WebRTC calls — what to collect and how?

Production media observability is built on aggregating client `getStats()` plus server metrics into a queryable pipeline.

```javascript
// Client: sample, summarize, and ship (don't flood — send rollups every ~10s + on events).
async function collectCallMetrics(pc, callId) {
  const stats = await pc.getStats();
  const sample = { callId, ts: Date.now(), inbound: [], outbound: [], pair: null };
  stats.forEach(r => {
    if (r.type === 'inbound-rtp')  sample.inbound.push({ kind: r.kind, lost: r.packetsLost, jitter: r.jitter, fps: r.framesPerSecond, freezes: r.freezeCount, nack: r.nackCount });
    if (r.type === 'outbound-rtp') sample.outbound.push({ kind: r.kind, bitrate: r.targetBitrate, rid: r.rid, qualityLimitationReason: r.qualityLimitationReason });
    if (r.type === 'candidate-pair' && r.nominated) sample.pair = { rtt: r.currentRoundTripTime, sendBw: r.availableOutgoingBitrate, localType: r.localCandidateType };
  });
  navigator.sendBeacon('/metrics', JSON.stringify(sample));  // sendBeacon survives page unload
}
```

What to track and *why*:
- **Connection success rate & candidate-type distribution** (host/srflx/relay) → spot NAT/firewall problems and TURN-fallback rate.
- **`qualityLimitationReason`** (`cpu` | `bandwidth` | `other`) on outbound → distinguish encoder CPU limits from network limits.
- **freezeCount / totalFreezesDuration, fps, jitter, loss** → user-perceived quality; build a per-call MOS-like score.
- **Server-side**: per-SFU egress bandwidth, CPU, forwarded-stream count, PLI/keyframe rate, per-room participant count.
- **Pipeline**: client beacons → ingest → time-series store (per-call drill-down + fleet dashboards) + alerting on success-rate / freeze-rate regressions. Tag by region, ISP, device, browser to localize issues (the classic "only this ISP fails" finding).

#### Q90. [Theory] What are the security threats specific to WebRTC (IP leakage, TURN abuse, SDP injection) and their mitigations?

WebRTC's NAT traversal and P2P nature introduce attack surface beyond normal web apps:

- **Local IP leakage / fingerprinting**: ICE candidate gathering can expose a user's **private (and public) IP addresses** to script — historically usable to deanonymize VPN/Tor users. Mitigations: browsers now apply **mDNS-obfuscated host candidates** (`.local` names instead of raw `192.168.x.x`) by default, and the `iceTransportPolicy`/privacy settings can force relay-only. Don't gather candidates before you actually need a connection.
- **TURN relay abuse / cost**: open or static TURN credentials let attackers use your relay as a **bandwidth amplifier / open proxy**. Mitigation: **time-limited HMAC credentials** (REST scheme), per-user quotas, allow-listing, and monitoring relay usage; never embed long-lived secrets in the client.
- **SDP / signaling injection & MITM**: since SDP carries the DTLS fingerprint that anchors media security, a tampered signaling channel can swap the fingerprint and MITM the media. Mitigation: run signaling over **TLS (WSS/HTTPS)**, authenticate users, and for the highest bar add **E2EE (SFrame/MLS)** so even a compromised server/SFU can't read media.
- **Denial of service**: STUN/TURN servers and SFUs are UDP-facing; mitigate with rate limiting, amplification protections, and capacity isolation per room.
- **Permission/UI spoofing**: ensure capture only starts on genuine user intent (gesture), and surface clear in-call indicators (the camera light + app UI).

The throughline: **secure the signaling channel** (it anchors media auth), **lock down TURN** (cost + proxy abuse), and **minimize IP exposure** (mDNS/relay), adding **E2EE** when the server itself must be untrusted.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q91. [Practical] A user reports "I can't see the other person, but they can see me." Where do you start?

This is **one-way media**, a classic WebRTC symptom, and the asymmetry is the key clue: the path *from* them *to* you is failing while the reverse works. Walk it layer by layer:

1. **Confirm tracks were added and negotiated.** On your side, check `pc.getReceivers()` has a video receiver with a live `track`, and that the remote SDP actually contained a `sendonly`/`sendrecv` video m-line. A missing remote track means a negotiation/`addTrack` bug, not a network issue.
2. **Check `ontrack` fired.** If it never fired, the remote never sent or the m-line direction was wrong on their side.
3. **Inspect ICE.** One-way media very often means the **selected candidate pair only works in one direction** — common with asymmetric NAT/firewall rules where their inbound UDP to you is blocked. Look at `getStats()` for `candidate-pair` `bytesReceived` staying 0 while `bytesSent` climbs.
4. **Verify the element is rendering.** `remoteVideo.srcObject` set? Autoplay blocked (no `muted`/no gesture)? `readyState` of the inbound track `live`?

```javascript
const recv = pc.getReceivers().find(r => r.track?.kind === 'video');
console.log('have inbound video track:', !!recv, recv?.track.readyState);
const stats = await pc.getStats();
for (const r of stats.values())
  if (r.type === 'inbound-rtp' && r.kind === 'video')
    console.log('bytesReceived:', r.bytesReceived, 'packetsReceived:', r.packetsReceived);
```

If `packetsReceived` is 0 → it's network/ICE (firewall, missing TURN). If packets arrive but nothing renders → it's the element/autoplay layer.

#### Q92. [Practical] `getUserMedia` rejects with `NotReadableError` / `TrackStartError`. What does it mean and how do you handle it?

`NotReadableError` (Chrome) / `AbortError` with `TrackStartError` (older Firefox) means the OS or hardware **could not deliver frames even though permission was granted** — the device is busy (another app or tab holds it), a driver glitch, or (on Windows) the camera privacy switch is engaged. It's distinct from `NotAllowedError` (user denied) and `NotFoundError` (no such device).

```javascript
async function getCamera(constraints) {
  try {
    return await navigator.mediaDevices.getUserMedia(constraints);
  } catch (err) {
    switch (err.name) {
      case 'NotAllowedError':  throw new UserFacing('Permission denied — enable camera in site settings.');
      case 'NotFoundError':    throw new UserFacing('No camera/mic found.');
      case 'NotReadableError': // hardware busy
        // Retry once with audio-only, or prompt the user to close other apps.
        return navigator.mediaDevices.getUserMedia({ audio: constraints.audio });
      case 'OverconstrainedError':
        // A constraint (e.g. exact deviceId/resolution) can't be met — relax and retry.
        return navigator.mediaDevices.getUserMedia({ audio: true, video: true });
      default: throw err;
    }
  }
}
```

The practical fix in the field is usually "another app/tab is using the camera" — surface that explicitly, and degrade to audio-only so the call still works.

#### Q93. [Practical] Remote video stays black even though `ontrack` fired and `packetsReceived` is climbing. What's wrong?

Packets arriving but a black frame means the issue is **downstream of the network** — the decoder or the rendering element:

- **Autoplay policy**: the most common cause. Browsers block autoplay of media with audio unless muted or there was a user gesture. The `<video>` silently stays black. Fix: set `muted` + `autoplay` + `playsinline`, or call `videoEl.play()` from a click handler and catch the rejection.
- **No keyframe yet**: the decoder can't render until it receives an intra (key) frame. If the publisher just started or you joined mid-stream, you see black until the next keyframe — send a PLI or ensure the SFU caches/forwards a keyframe to new subscribers.
- **`srcObject` not actually set**, or set before the element is in the DOM.
- **`playsinline` missing on iOS Safari**, which otherwise tries to go fullscreen and can render nothing inline.

```javascript
remoteVideo.autoplay = true;
remoteVideo.muted = true;          // required for unattended autoplay
remoteVideo.playsInline = true;    // iOS Safari
remoteVideo.srcObject = remoteStream;
remoteVideo.play().catch(() => showTapToUnmuteOverlay());  // user-gesture fallback
```

Decoder-stall (no keyframe) vs. autoplay-block are the two big ones; check `getStats` `framesDecoded` — if it stays 0 with packets arriving, it's a keyframe/decoder problem, not autoplay.

#### Q94. [Practical] How do you reliably show "the other person is muted" in the UI?

`track.enabled = false` (their app mute) does **not** change anything observable on your side — RTP keeps flowing with silence/black, so you can't detect it from the media alone. Mute state must be **signaled out of band** (over your data channel or signaling server) as application state.

```javascript
// Sender: tell peers when you mute, in addition to toggling the track.
function setMuted(track, muted, signaling) {
  track.enabled = !muted;
  signaling.send({ type: 'mute-state', kind: track.kind, muted });
}

// Receiver: render the indicator from the signaled state, not the media.
signaling.on('mute-state', ({ kind, muted }) => updateMuteIcon(kind, muted));
```

The `MediaStreamTrack.muted` property is a red herring here — it reflects the *source* being temporarily dry (OS/network), not the remote user's intent. For active-speaker glow you can use audio levels (`getStats` `audioLevel` or the Web Audio API), but for a deliberate mute icon, signal it explicitly.

#### Q95. [Coding] Detect who is speaking using audio levels for an active-speaker indicator.

Use the Web Audio API `AnalyserNode` on each remote (or local) audio track to compute a running volume, then threshold it.

```javascript
function trackSpeaking(stream, onLevel) {
  const ctx = new AudioContext();
  const source = ctx.createMediaStreamSource(stream);
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 512;
  source.connect(analyser);
  const data = new Uint8Array(analyser.frequencyBinCount);

  let raf;
  (function loop() {
    analyser.getByteTimeDomainData(data);
    // RMS around the 128 midpoint → rough loudness.
    let sum = 0;
    for (const v of data) { const d = v - 128; sum += d * d; }
    const rms = Math.sqrt(sum / data.length) / 128;       // 0..1
    onLevel(rms, rms > 0.05);                              // speaking threshold
    raf = requestAnimationFrame(loop);
  })();

  return () => { cancelAnimationFrame(raf); ctx.close(); };
}

const stop = trackSpeaking(remoteStream, (level, speaking) =>
  tile.classList.toggle('speaking', speaking));
```

Add hysteresis (require N consecutive frames over threshold, and a decay before clearing) so the indicator doesn't flicker. In an SFU world you'd prefer the server's `audio-level` RTP header extension for active-speaker, but client-side analysis is fine for local feedback and small calls.

#### Q96. [Practical] Your call connects on Wi-Fi but fails on the office network. What's the likely cause and fix?

Corporate/guest networks frequently **block UDP entirely** and/or use **symmetric NAT** with strict firewalls. WebRTC's preferred path (direct UDP, STUN-assisted) can't form, so the call fails if you have no working fallback. The fixes, in order of importance:

1. **Deploy TURN, and include `turns:` over TCP/443.** A UDP-only TURN won't help if UDP is blocked. `turns:turn.example.com:443?transport=tcp` masquerades as HTTPS and traverses almost any firewall.
2. **Verify credentials and reachability** — test `iceTransportPolicy: 'relay'` to force the relay path and confirm TURN actually works in isolation.
3. **Check the candidate types** in `getStats`: if you only ever see `host`/`srflx` and never `relay`, your TURN isn't being used (bad creds/URL); if you see relay candidates but they fail, the firewall blocks even 443.

```javascript
// Diagnostic: force relay-only to test whether TURN works at all.
const pc = new RTCPeerConnection({ iceServers, iceTransportPolicy: 'relay' });
```

The single most common production miss is shipping only `turn:...?transport=udp` and being surprised that locked-down enterprise networks (which block UDP) can't connect. Always have a TCP/TLS-443 TURN candidate.

#### Q97. [Practical] How do you test a WebRTC app locally across two "peers" and simulate bad networks?

Several practical techniques:

- **Two tabs / two `RTCPeerConnection`s in one page**: the simplest loopback — wire the two PCs' `onicecandidate`/SDP directly to each other (no real signaling server) to exercise the negotiation and media path on `localhost` (a secure context, so `getUserMedia` works).
- **`chrome://webrtc-internals`**: the indispensable built-in. It dumps every PC's SDP, ICE candidate pairs, and live `getStats` graphs (bitrate, RTT, loss, fps) — your first stop for any quality bug.
- **Fake devices**: launch Chrome with `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream` to get a synthetic camera/mic and auto-grant permission for automated tests.
- **Network impairment**: `tc netem` on Linux (or Network Link Conditioner on macOS, Chrome DevTools throttling for data) to inject loss/latency/jitter and watch GCC adapt.

```bash
# Inject 200ms latency + 5% loss on the loopback to test resilience.
sudo tc qdisc add dev lo root netem delay 200ms loss 5%
# Remove it afterwards:
sudo tc qdisc del dev lo root netem
```

`webrtc-internals` plus `tc netem` covers ~90% of practical debugging without any external infrastructure.

#### Q98. [Coding] Build a minimal in-page loopback to test two peers without a signaling server.

```javascript
async function loopback() {
  const local = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
  const pc1 = new RTCPeerConnection();
  const pc2 = new RTCPeerConnection();

  // Wire ICE candidates directly between the two PCs (no network signaling).
  pc1.onicecandidate = ({ candidate }) => candidate && pc2.addIceCandidate(candidate);
  pc2.onicecandidate = ({ candidate }) => candidate && pc1.addIceCandidate(candidate);

  pc2.ontrack = ({ streams: [remote] }) => { document.querySelector('#remote').srcObject = remote; };

  local.getTracks().forEach(t => pc1.addTrack(t, local));

  // Manual offer/answer between the local objects.
  const offer = await pc1.createOffer();
  await pc1.setLocalDescription(offer);
  await pc2.setRemoteDescription(offer);
  const answer = await pc2.createAnswer();
  await pc2.setLocalDescription(answer);
  await pc1.setRemoteDescription(answer);
}
```

This exercises the full negotiation, ICE, DTLS, and media pipeline entirely in one tab on `localhost` — ideal for unit/integration tests of your media wiring before any signaling server exists.

#### Q99. [Practical] After ending a call, the camera light stays on. What did you forget?

Closing the `RTCPeerConnection` (`pc.close()`) tears down the transport but does **not** release the capture hardware — the `MediaStreamTrack`s you got from `getUserMedia` are still live. You must explicitly `stop()` every track.

```javascript
function endCall(pc, localStream) {
  pc.close();                                  // closes transports, stops sending/receiving
  localStream.getTracks().forEach(t => t.stop()); // releases camera/mic → light goes off
  localVideo.srcObject = null;                 // detach from the element
  // Also stop any screen-share / processed tracks you created separately.
}
```

A lingering camera light is almost always a forgotten `track.stop()` — and on a SPA where the user navigates away without unmounting properly, the tracks (and the light) persist until GC, which is both a privacy concern and a bug. Stop tracks in your cleanup/`unmount` path, not just on the explicit hang-up button.

#### Q100. [Practical] The call works for two people but a third joining sees/hears nothing. What's the architectural smell?

If your app was built 1:1 (a single `RTCPeerConnection` between two peers) and you tried to add a third by reusing the same pattern, it breaks because **mesh requires each peer to hold a separate `RTCPeerConnection` to every other peer**. A third participant needs two connections (to each existing peer), and each existing peer needs a new connection to the newcomer.

```
2 people: 1 connection
3 people: 3 connections (A-B, A-C, B-C) — each peer holds 2 PCs
```

The smell is a single global `pc` variable instead of a `Map<peerId, RTCPeerConnection>`. The real fix beyond ~3–4 people is to stop doing mesh and **introduce an SFU**, so each client holds exactly one connection (to the server) regardless of participant count. If you're hand-rolling mesh, you also need per-peer offer/answer and per-peer track management — which is exactly the complexity that motivates moving to an SFU early.

### 🟡 — extended

#### Q101. [Practical] Users complain the call "lags and freezes" intermittently. How do you methodically diagnose it from the client?

Don't guess — pull `getStats` and classify. Freezing is a *symptom*; the cause is one of CPU, bandwidth, or loss, and the stats distinguish them:

```javascript
async function diagnose(pc) {
  const stats = await pc.getStats();
  const out = {};
  for (const r of stats.values()) {
    if (r.type === 'outbound-rtp' && r.kind === 'video')
      out.sendReason = r.qualityLimitationReason;     // 'cpu' | 'bandwidth' | 'none'
    if (r.type === 'inbound-rtp' && r.kind === 'video') {
      out.freezes = r.freezeCount;
      out.lossPct = r.packetsLost / (r.packetsReceived + r.packetsLost);
      out.jitter = r.jitter;
      out.fps = r.framesPerSecond;
    }
    if (r.type === 'candidate-pair' && r.nominated) {
      out.rtt = r.currentRoundTripTime;
      out.recvBw = r.availableIncomingBitrate;
    }
  }
  return out;
}
```

Read the signals:
- `qualityLimitationReason === 'cpu'` → the *sender's* machine can't encode fast enough (old laptop, too many simulcast layers) → reduce resolution/fps or drop a layer.
- `qualityLimitationReason === 'bandwidth'` + falling `availableIncomingBitrate` → network congestion → GCC is already lowering bitrate; freezes mean it can't keep up.
- High `jitter` + nonzero `packetsLost` → lossy/variable network → jitter buffer is stretching; nothing the app can do but adapt down.
- `rtt` spikes → geography/congestion → move media servers closer.

This turns a vague "it lags" into a specific, actionable root cause.

#### Q102. [Practical] What is `qualityLimitationReason` and how do you act on each value?

`qualityLimitationReason` (on `outbound-rtp` video stats) tells you **why the encoder isn't sending full quality** — the single most useful field for sender-side quality debugging:

- **`none`** — not limited; you're sending at the requested quality.
- **`cpu`** — encoding is CPU-bound. The machine can't encode the requested resolution/fps/layers in real time. Act: lower resolution (`scaleResolutionDownBy`), cap `maxFramerate`, drop a simulcast layer, or prefer a hardware-accelerated codec (H.264/AV1 HW).
- **`bandwidth`** — GCC has throttled the bitrate because the network can't carry it. Act: nothing forced is needed (GCC already adapts), but you can drop the top layer to free headroom for audio, or switch to a more efficient codec.
- **`other`** — implementation-specific (e.g. resolution constraints).

```javascript
if (out.sendReason === 'cpu') {
  const p = sender.getParameters();
  p.encodings[0].scaleResolutionDownBy = 2;  // shrink to relieve the encoder
  p.encodings[0].maxFramerate = 20;
  await sender.setParameters(p);
}
```

The crucial distinction: `cpu` is *your machine's* problem (fixable by encoding less), `bandwidth` is *the network's* problem (fixable by sending fewer bits). Treating a CPU limit as a bandwidth limit (or vice versa) sends you down the wrong path.

#### Q103. [Coding] Implement automatic reconnection with exponential backoff when the connection drops.

Network changes (Wi-Fi → cellular) or transient outages move `connectionState` to `disconnected`/`failed`. Handle `disconnected` with a grace period (often it self-heals), and escalate to ICE restart, then full rebuild.

```javascript
function manageConnection(pc, signaling, rebuild) {
  let graceTimer = null;
  let attempt = 0;

  pc.onconnectionstatechange = async () => {
    switch (pc.connectionState) {
      case 'connected':
        clearTimeout(graceTimer); attempt = 0; break;

      case 'disconnected':
        // Often transient — wait before reacting.
        graceTimer = setTimeout(() => maybeRestart(), 3000);
        break;

      case 'failed':
        clearTimeout(graceTimer);
        maybeRestart();
        break;
    }
  };

  async function maybeRestart() {
    const delay = Math.min(30000, 500 * 2 ** attempt) + Math.random() * 500; // backoff + jitter
    attempt++;
    await new Promise(r => setTimeout(r, delay));
    if (pc.connectionState === 'connected') return;       // recovered while waiting
    if (attempt <= 3) {
      const offer = await pc.createOffer({ iceRestart: true });
      await pc.setLocalDescription(offer);
      signaling.send({ type: 'offer', sdp: pc.localDescription });
    } else {
      rebuild();   // give up on this PC; tear down and create a fresh one
    }
  }
}
```

Key practices: a grace period for `disconnected`, ICE restart before a full rebuild, exponential backoff with jitter to avoid thundering-herd reconnects, and a cap after which you rebuild the whole `RTCPeerConnection`.

#### Q104. [Practical] A user switches from Wi-Fi to cellular mid-call. What happens and how do you keep the call alive?

The device's IP changes, so the existing ICE candidate pair becomes invalid — media stalls and `connectionState` goes `disconnected` → `failed`. The recovery mechanism is **ICE restart**, which re-gathers candidates on the new interface and re-nominates a working pair *without* tearing down DTLS or the media tracks.

```javascript
pc.onconnectionstatechange = () => {
  if (pc.connectionState === 'failed') pc.restartIce();   // triggers negotiationneeded
};
pc.onnegotiationneeded = async () => {
  await pc.setLocalDescription();                          // fresh offer w/ new ICE creds
  signaling.send({ description: pc.localDescription });
};
```

Modern enhancement: some stacks pre-gather candidates on multiple interfaces or use **continuous nomination** so the switch is near-seamless. On mobile, also handle app backgrounding (the OS may suspend the connection) by re-checking state on `visibilitychange`/resume. The user perceives a 1–3 second blip rather than a dropped call — provided your signaling channel survives the network change too (also reconnect the WebSocket).

#### Q105. [Coding] Replace the camera track without renegotiation and keep simulcast working.

`replaceTrack` swaps the source on an existing sender with no SDP exchange, preserving the simulcast encodings configured on that sender.

```javascript
async function switchCamera(pc, deviceId) {
  const newStream = await navigator.mediaDevices.getUserMedia({
    video: { deviceId: { exact: deviceId } }
  });
  const newTrack = newStream.getVideoTracks()[0];

  const sender = pc.getSenders().find(s => s.track?.kind === 'video');
  await sender.replaceTrack(newTrack);   // encodings (simulcast layers) persist

  // Stop the old track to release the previous camera.
  // (sender.track was the old one before replace; capture it first if needed.)
  document.querySelector('#local').srcObject = newStream;
  return newTrack;
}
```

Because the sender object and its `sendEncodings` are untouched, the three simulcast layers keep being produced from the new source — no renegotiation, no layer re-setup, no flicker for receivers. This is the correct pattern for camera switching, applying a background-blur processed track, and toggling screen share (replace video track), whereas `addTrack`/`removeTrack` would force renegotiation.

#### Q106. [Practical] Screen sharing works but the shared screen is choppy / low frame rate. How do you tune it?

Screen content has different needs than camera video: high resolution, often static, with bursts of motion (scrolling, video). Default camera-tuned encoding handles it poorly. Tuning levers:

- **`contentHint`**: tell the encoder the content type. `track.contentHint = 'detail'` (or `'text'`) prioritizes **resolution/sharpness** (good for documents/code) at lower frame rate; `'motion'` prioritizes **frame rate** (good for sharing a video). This is the single biggest lever.
- **Constraints on `getDisplayMedia`**: request an appropriate `frameRate` — 5–15 fps for static content saves huge bandwidth; 30 fps only if sharing motion.
- **Bitrate**: screen content at 1080p+ needs a higher `maxBitrate` than a 720p camera to stay sharp; bump it via `setParameters`.

```javascript
const stream = await navigator.mediaDevices.getDisplayMedia({
  video: { frameRate: { ideal: 15, max: 30 } }
});
const track = stream.getVideoTracks()[0];
track.contentHint = 'detail';     // optimize for sharp text over smooth motion

const sender = pc.getSenders().find(s => s.track?.kind === 'video');
await sender.replaceTrack(track);
const p = sender.getParameters();
p.encodings[0].maxBitrate = 2_500_000;   // give text room to stay crisp
await sender.setParameters(p);
```

If they're sharing a video, flip `contentHint = 'motion'` and raise the frame rate instead. Matching the hint to the actual content is what makes screen share look right.

#### Q107. [Practical] Audio has a robotic / underwater / choppy quality. What are the likely causes?

Robotic or warbling audio points to **packet loss and concealment** or **clock drift**, not bandwidth in the video sense:

- **Packet loss → PLC artifacts**: when Opus packets are lost, the decoder synthesizes substitute audio (packet loss concealment), producing the characteristic robotic/warbling sound. Confirm with `getStats` `packetsLost` and `concealedSamples`/`concealmentEvents` on the inbound audio. Mitigate by enabling **Opus in-band FEC** and DTX, and ensuring the network path/TURN isn't dropping packets.
- **Jitter buffer too small / too much jitter**: choppiness with gaps. The buffer is underrunning. High `jitter` in stats confirms it.
- **CPU starvation**: if the device is overloaded, audio processing stutters — check whether video `qualityLimitationReason` is `cpu` too.
- **Sample-rate / clock drift** between capture and playout devices causes periodic glitches; usually handled by the browser's resampler but bad on some hardware.
- **Echo cancellation / AGC fighting the signal** on certain setups — toggling `echoCancellation`/`autoGainControl` constraints can help diagnose.

```javascript
for (const r of (await pc.getStats()).values())
  if (r.type === 'inbound-rtp' && r.kind === 'audio')
    console.log({ lost: r.packetsLost, concealed: r.concealedSamples, jitter: r.jitter });
```

Rising `concealedSamples` is the smoking gun for loss-induced robotic audio.

#### Q108. [Coding] Cap the outbound bitrate at runtime (e.g. a "low data mode" toggle) without renegotiating.

Use `RTCRtpSender.setParameters()` — read, mutate, set. No SDP munging, no renegotiation.

```javascript
async function setDataMode(pc, mode /* 'low' | 'normal' */) {
  const sender = pc.getSenders().find(s => s.track?.kind === 'video');
  const params = sender.getParameters();
  if (!params.encodings.length) params.encodings = [{}];   // safety

  if (mode === 'low') {
    params.encodings.forEach((e, i) => {
      e.maxBitrate = i === 0 ? 150_000 : 0;   // keep only the lowest layer alive
      e.active = i === 0;                      // disable higher simulcast layers
      e.scaleResolutionDownBy = 4;
      e.maxFramerate = 15;
    });
  } else {
    params.encodings.forEach(e => { e.active = true; e.maxBitrate = undefined; e.scaleResolutionDownBy = 1; });
  }
  await sender.setParameters(params);          // applies immediately
}
```

Setting `active = false` on higher simulcast encodings stops producing those layers entirely (saving CPU and uplink), while the lowest stays for basic video. This is exactly how a "save data" or "audio-priority" toggle should be implemented — runtime, per-encoding, no negotiation.

#### Q109. [Practical] Your WebSocket signaling reconnects, but calls already in progress don't recover state. How do you design signaling for resilience?

Signaling and media are separate channels — the media (P2P/SFU) can survive a brief signaling outage, but you lose the ability to renegotiate, trickle late candidates, or handle ICE restart while signaling is down. Design for it:

- **Make signaling reconnect automatically** with backoff, and on reconnect **resync session state** (who's in the room, current SDP/transceiver state) rather than assuming a clean slate.
- **Idempotent, sequenced messages**: tag signaling messages with a monotonically increasing sequence/version so a reconnect can detect and replay/skip missed messages. Don't apply a stale offer.
- **Buffer outbound signaling** while disconnected and flush on reconnect (with dedup), so a candidate generated during the outage isn't lost.
- **Decouple liveness**: don't tear down a healthy media connection just because the signaling socket blipped — only act on `pc.connectionState`, not the WebSocket state.
- **Server-side session ownership** (room state in Redis, etc.) so a reconnecting client re-attaches to the existing session instead of creating a duplicate.

The mental model: *media is the data plane, signaling is the control plane*; the control plane must be reconnectable and resyncable without disturbing a healthy data plane.

#### Q110. [Practical] How do you record a call client-side, and when is that the wrong approach?

`MediaRecorder` can capture a `MediaStream` to a file in the browser:

```javascript
function recordStream(stream, onData) {
  const rec = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp9,opus' });
  const chunks = [];
  rec.ondataavailable = e => { if (e.data.size) chunks.push(e.data); };
  rec.onstop = () => onData(new Blob(chunks, { type: 'video/webm' }));
  rec.start(1000);   // emit a chunk every second
  return rec;        // rec.stop() to finish
}
```

But client-side recording is the **wrong approach for most production needs** because: it only captures what *that one client* sees (one perspective, subject to their network loss/freezes — the recording inherits every glitch), it competes for the user's CPU, it's lost if the tab crashes, and it can't reliably composite a multi-party layout. For real recording, do **server-side egress** (a headless browser or media pipeline joins the room and records centrally to object storage) — faithful, reliable, and independent of any participant's device. Client recording is acceptable only for quick local clips, voice memos, or when you explicitly want a single user's view.

### 🟠 — extended

#### Q111. [Practical] In your SFU app, new participants joining mid-call see a frozen/black tile for several seconds. Why, and how do you fix it?

Video decoding requires a **keyframe** (intra frame) to start; inter frames reference previous frames the newcomer never received. A new subscriber joins between keyframes and must wait — by default up to the encoder's keyframe interval (which can be seconds). The fixes:

- **Request a keyframe (PLI) when a subscriber joins** so the publisher emits a fresh intra frame promptly. The SFU sends the PLI upstream.
- **Cache the latest keyframe at the SFU** and forward it immediately to new subscribers as a bootstrap, avoiding a publisher round-trip and not disturbing existing viewers.
- **Coalesce PLIs**: if many subscribers join at once, don't forward N PLIs to the publisher (keyframe storm) — throttle to one and fan the resulting keyframe out to all newcomers.

```
Naive:   subscriber joins → wait for next periodic keyframe (1–4s of black)
Better:  subscriber joins → SFU sends cached keyframe immediately → renders fast
Best:    + coalesced PLI so 50 simultaneous joins don't flood the publisher
```

Keyframes are large, so the art is making them available to newcomers without triggering them so often that they congest the stream for everyone (the keyframe-storm failure mode).

#### Q112. [Practical] How do you debug a "keyframe storm" where the publisher's bitrate spikes and everyone's video stutters?

A keyframe storm is when too many keyframes are generated in a short window — keyframes are several times larger than inter frames, so a burst spikes bitrate, congests the path, and causes loss/stutter for *all* subscribers. Diagnose and fix:

- **Symptom in stats**: publisher `outbound-rtp` shows `keyFramesEncoded` climbing rapidly and bitrate sawtoothing; subscribers see periodic loss/freezes synchronized with the spikes.
- **Common triggers**: every subscriber join/layer-switch sending an un-throttled PLI; aggressive packet loss causing repeated PLI/FIR; a recording egress also requesting keyframes; misconfigured very-short keyframe interval.
- **Fixes**: **coalesce/rate-limit PLI** at the SFU (one keyframe request per publisher per short window, regardless of how many subscribers asked); **cache and reuse** the last keyframe for new joiners; ensure the encoder's keyframe interval isn't pathologically short; and separate the recording path so it doesn't independently hammer keyframes.

The root insight: keyframe requests are a **shared cost on the publisher** that affects everyone, so they must be aggregated centrally at the SFU, never forwarded one-per-subscriber.

#### Q113. [Coding] At the SFU/consumer level, adapt the forwarded simulcast layer to each subscriber's downlink with hysteresis.

```javascript
// Per-subscriber adaptation loop. Hysteresis prevents thrashing between layers.
function makeLayerSelector(consumer) {
  let current = 2;                       // start at top spatial layer
  let stableSince = Date.now();
  const UP_THRESHOLD = 0.85, DOWN_THRESHOLD = 0.6, DWELL = 4000;

  return function adapt({ availableOutgoingBitrate, layerBitrates }) {
    const need = layerBitrates[current];                  // bps this layer wants
    const headroom = availableOutgoingBitrate / need;     // >1 = comfortable

    let target = current;
    if (headroom < DOWN_THRESHOLD && current > 0) target = current - 1;   // downgrade fast
    else if (headroom > 1 / UP_THRESHOLD && current < 2                    // upgrade slow
             && Date.now() - stableSince > DWELL) target = current + 1;

    if (target !== current) {
      consumer.setPreferredLayers({ spatialLayer: target });
      current = target;
      stableSince = Date.now();
    }
  };
}
```

Principles encoded here: **downgrade quickly** (protect against congestion) but **upgrade slowly** (require a dwell time at the lower layer first) to avoid oscillation; use a bitrate *headroom ratio* rather than absolute thresholds; and switch only on layer boundaries the SFU can serve. Per-subscriber selection means one congested viewer never degrades others.

#### Q114. [Practical] A subset of users on one ISP have a 30% call-failure rate while everyone else is fine. How do you isolate it?

This is the classic "only-this-ISP" problem, and the method is **aggregate `getStats` telemetry tagged by dimension**:

1. **Instrument every call** to beacon key fields: selected `candidate-pair` type (host/srflx/relay), `transport` (udp/tcp), connection success/fail, ICE state transitions, and quality stats — tagged with **ISP/ASN, region, browser, OS, device**.
2. **Slice by tag.** Here you'd find the failing cohort all share an ASN and almost always end on `relay`/`tcp` candidates or fail to connect at all — pointing at that ISP blocking UDP or doing carrier-grade NAT that defeats STUN.
3. **Form a hypothesis and test it**: force `iceTransportPolicy: 'relay'` for that cohort, confirm TURN-over-443 reachability, check whether your TURN region is geographically far from that ISP (high RTT → timeouts).
4. **Fix systemically**: add a closer TURN PoP, ensure `turns:443/tcp` is offered, raise ICE timeouts for that path, and add a **permanent dashboard** for candidate-type distribution and per-ASN success rate so the next such issue is visible immediately.

The discipline is *measure in aggregate, slice by dimension, never debug a network you can't reproduce by guessing* — the data localizes it for you.

#### Q115. [Practical] How do you implement a "raise hand to speak" promotion from viewer to publisher in an SFU broadcast?

In an asymmetric broadcast (few publishers, many WHEP/recv-only viewers), promotion means **giving a viewer a send transport and adding their tracks** to the room without disturbing the broadcast:

1. **Authorization**: the viewer requests promotion over signaling; the server checks policy/moderation and grants a token scoped to publish.
2. **Add a send transport / transceiver**: client calls `getUserMedia`, adds the track (`addTrack` or flips a pre-created `recvonly` transceiver to `sendrecv`), and renegotiates with the SFU. Pre-creating an inactive send transceiver at join time makes promotion faster (no cold transport setup).
3. **SFU subscribes others to the new publisher**: the server starts forwarding the promoted user's stream to the audience (or to the panelists), respecting per-subscriber layer budgets.
4. **Demotion**: on "lower hand," stop the track, set the transceiver back to `recvonly`/`inactive`, and the SFU stops forwarding it.

```javascript
async function raiseHand(pc, signaling) {
  const mic = (await navigator.mediaDevices.getUserMedia({ audio: true })).getAudioTracks()[0];
  const sender = pc.getSenders().find(s => s.track === null && /* a pre-added send slot */ true);
  if (sender) await sender.replaceTrack(mic);            // fast path: reuse pre-created transceiver
  else pc.addTrack(mic);                                  // slow path: triggers renegotiation
  signaling.send({ type: 'request-promotion' });
}
```

The key design move is **pre-provisioning idle send transceivers** so promotion is a `replaceTrack` (instant) rather than a full transport+negotiation cold start under the spotlight.

#### Q116. [Coding] Add E2EE to media using the encoded-transform API so the SFU can't read the payload.

`RTCRtpScriptTransform` (encoded transform) runs in a Worker and lets you encrypt each frame's payload after encoding and before SRTP, so the SFU forwards opaque bytes.

```javascript
// main.js — attach a transform to the sender and receiver.
const worker = new Worker('e2ee-worker.js');
const sender = pc.getSenders().find(s => s.track?.kind === 'video');
sender.transform = new RTCRtpScriptTransform(worker, { operation: 'encrypt', keyId: 1 });

const receiver = pc.getReceivers().find(r => r.track?.kind === 'video');
receiver.transform = new RTCRtpScriptTransform(worker, { operation: 'decrypt', keyId: 1 });
```

```javascript
// e2ee-worker.js — transform each encoded frame's payload.
onrtctransform = (event) => {
  const { readable, writable } = event.transformer;
  const op = event.transformer.options.operation;
  readable.pipeThrough(new TransformStream({
    async transform(frame, controller) {
      const data = new Uint8Array(frame.data);
      // Leave the codec header bytes in the clear so the SFU can still parse/route;
      // encrypt only the payload after the unencrypted header offset.
      const header = unencryptedHeaderLength(frame);     // codec-specific
      const payload = data.subarray(header);
      const out = op === 'encrypt'
        ? await aesGcmEncrypt(payload, key, deriveIv(frame))
        : await aesGcmDecrypt(payload, key, deriveIv(frame));
      data.set(out, header);
      frame.data = data.buffer;
      controller.enqueue(frame);
    }
  })).pipeTo(writable);
};
```

Critical details: keep the **codec header in the clear** so the SFU can still depacketize and select layers; the key is shared **only among participants** (via a group key-agreement like MLS), never with the server; and rotate keys on membership change. The SFU routes and reads RTP/codec headers but the payload is unreadable — true server-blind E2EE.

#### Q117. [Practical] How do you keep TURN costs under control without hurting connectivity?

TURN relays your media (egress bandwidth = real money) and is the safety net you can't remove. Balancing cost vs. reliability:

- **Don't force relay**: keep `iceTransportPolicy: 'all'` so the ~80–90% of calls that *can* go direct/STUN do, and only the remainder use TURN. Forcing relay for everyone is the most expensive mistake.
- **Time-limited HMAC credentials** with per-user/per-session quotas so the relay can't be abused as an open proxy (which is unbounded cost).
- **Place TURN PoPs near users** (geo-routed/anycast) — closer relays mean lower RTT *and* less long-haul bandwidth cost, and they keep the relayed path usable.
- **Prefer UDP relay** (cheaper, lower latency) and only fall to TCP/TLS-443 when UDP is blocked; monitor the UDP:TCP relay ratio.
- **Measure relay-fallback rate per region/ISP**: a spike means a connectivity regression (or abuse) — and tells you where direct paths are failing so you can investigate rather than just pay more.
- **Cap per-stream relay bitrate** sensibly so a few heavy relayed calls don't blow the budget.

The governing principle: TURN is insurance — minimize how often you claim it (maximize direct/STUN success) and lock down who can, rather than removing the coverage.

#### Q118. [Practical] After a renegotiation (adding screen share), audio briefly cuts out. What likely went wrong?

Adding a track that triggers renegotiation can disrupt media if the renegotiation is handled carelessly — common culprits:

- **Recreating transceivers / m-line reordering**: if your code removes and re-adds tracks (or your SFU shuffles m-lines) instead of adding a new transceiver, the existing audio transceiver's `mid` can shift, causing a momentary teardown. In Unified Plan, **add** a new transceiver for the screen share rather than disturbing existing ones.
- **Glare**: both sides renegotiated at once (you added screen share while they added something), the offer collided, and one side rolled back — dropping media briefly. Use **perfect negotiation** (polite/impolite) to handle it cleanly.
- **Full SDP renegotiation when `replaceTrack` would do**: if you're merely swapping a video source (camera→screen on the *same* sender), use `replaceTrack` (no renegotiation, no audio disruption). Renegotiate only when genuinely adding a new media line.
- **Bundling/DTLS restart**: a bug that changes ICE/DTLS parameters on renegotiation forces a transport restart that interrupts all media, not just video.

The fix is usually "add a transceiver, don't rebuild," plus perfect negotiation to avoid glare — so the audio transceiver is never touched when you add screen share.

#### Q119. [Coding] Instrument a production-grade stats collector that beacons quality metrics for a call.

```javascript
function startQualityBeacon(pc, { callId, region }, send) {
  let last = {};
  const interval = setInterval(async () => {
    const stats = await pc.getStats();
    const sample = { callId, region, t: Date.now() };
    for (const r of stats.values()) {
      if (r.type === 'inbound-rtp' && r.kind === 'video') {
        // Convert cumulative counters into per-interval rates.
        const lost = r.packetsLost - (last.lost ?? r.packetsLost);
        const recv = r.packetsReceived - (last.recv ?? r.packetsReceived);
        sample.lossPct = recv > 0 ? lost / (lost + recv) : 0;
        sample.fps = r.framesPerSecond;
        sample.freezes = r.freezeCount;
        sample.jitter = r.jitter;
        last.lost = r.packetsLost; last.recv = r.packetsReceived;
      }
      if (r.type === 'outbound-rtp' && r.kind === 'video')
        sample.limit = r.qualityLimitationReason;
      if (r.type === 'candidate-pair' && r.nominated) {
        sample.rtt = r.currentRoundTripTime;
        sample.candType = r.remoteCandidateType;   // host | srflx | relay
        sample.recvBw = r.availableIncomingBitrate;
      }
    }
    send('/beacon', sample);                        // navigator.sendBeacon in prod
  }, 5000);
  return () => clearInterval(interval);
}
```

Notes for production: use `navigator.sendBeacon` (survives page unload), **rate-diff cumulative counters** (most stats are monotonic totals, not per-interval), keep the sample small, and tag with region/ISP/browser so you can slice. This is the telemetry backbone that turns "it's slow" into a dashboard you can alert on.

#### Q120. [Practical] How do you handle a participant on a very slow connection in a group call so they don't degrade everyone?

The slow participant has two roles — as a **sender** and as a **receiver** — and each is bounded independently so the rest of the room is unaffected (the whole point of the SFU model):

- **As a receiver**: the SFU forwards them only the **lowest simulcast/SVC layer** (or pauses their video and keeps audio). Per-subscriber layer selection means their tiny downlink doesn't force anyone else down.
- **As a sender**: their GCC throttles their uplink, so they may only publish a low layer (or audio only). The SFU offers whatever layers they manage to send; other participants subscribe to a low-quality version of *that one user* but full quality of everyone else.
- **Audio-first fallback**: when their link is dire, **suspend their video both ways** and keep audio (the priority for communication), with a UI hint ("Your connection is unstable — video paused").
- **Hysteresis**: don't flap them in and out; require sustained improvement before restoring video.

```
Slow user's downlink → SFU sends them 180p only (others unaffected)
Slow user's uplink   → they publish 180p/audio only (others see them small, full quality among themselves)
Dire                 → audio-only both directions + UI hint
```

The architecture isolates the slow peer: their problem is *theirs*, surfaced gracefully, never propagated to the room.

### 🔴 — extended

#### Q121. [Practical] You're migrating a working app from mesh to an SFU and quality regressed. What changed and how do you reason about it?

Moving from mesh to SFU changes the media topology in ways that can hurt quality if not accounted for:

- **An extra hop**: media now goes client → SFU → client instead of direct P2P, adding the server's RTT. If the SFU is geographically far from users, end-to-end latency and loss rise. **Fix**: place SFUs close to users (regional PoPs), measure per-region RTT.
- **Loss of direct-path quality**: in mesh, two well-connected peers got the lowest-latency direct path; the SFU may route both through a distant box. **Fix**: geo-route to the nearest SFU; consider cascading.
- **Simulcast/codec mismatch**: mesh let each pair negotiate the best mutual codec; an SFU forwards without transcoding, so the **whole room is pinned to one common codec** — possibly a less efficient baseline (VP8/H.264) for compatibility. **Fix**: pick the best codec all clients support, or transcode for outliers.
- **Single congestion-controlled uplink**: in mesh you encoded per-peer; now you publish once with simulcast, and if simulcast isn't enabled the SFU can't adapt per receiver — everyone gets one bitrate. **Fix**: enable simulcast/SVC so the SFU can tailor per subscriber.
- **Keyframe/PLI dynamics**: the SFU centralizes keyframe handling; a misconfigured PLI policy causes storms that didn't exist in mesh.

The reasoning framework: enumerate what mesh gave for free (direct path, per-pair codec, per-peer encode) and verify the SFU replaces each (geo-proximity, common codec choice, simulcast, sane keyframe policy).

#### Q122. [Practical] Design the observability and on-call runbook for a production WebRTC platform. What do you watch and what do you do?

A media platform fails in ways HTTP monitoring won't catch, so you need media-specific SLOs and runbooks:

**Watch (golden signals):**
- **Connection success rate** (overall and sliced by region/ISP/browser/device) — the top-line SLO. A drop in one slice localizes the cause.
- **Candidate-type / relay-fallback distribution** — a rising relay rate signals a connectivity regression or UDP-blocking network.
- **Per-call quality**: freeze rate, p95 RTT, loss, fps, and a composite MOS-like score; alert on regressions, not just absolutes.
- **Server fleet**: per-SFU egress bandwidth, CPU, forwarded-stream count, PLI/keyframe rate, room counts; TURN relay bandwidth and cost.
- **Capacity headroom** and autoscale lag, especially before scheduled large events.

**Runbook examples:**
- *Success rate drops in one region* → check that region's SFU/TURN health, recent deploys, and whether an upstream network/ISP changed; fail over rooms to a healthy region.
- *Relay-fallback spikes* → verify TURN reachability (UDP and 443/TCP), check for an expired TURN secret or a PoP outage.
- *Keyframe/PLI rate spikes* → likely a keyframe storm or a buggy client cohort; throttle PLIs, identify the client version.
- *SFU CPU/egress saturating* → shed/rebalance rooms via the control plane, scale the edge pool.

**Principles**: client beacons + server metrics into a time-series store with per-call drill-down *and* fleet dashboards; alert on **rate-of-change** of success/freeze; tag everything by region/ISP/version so every page comes with a localization hint. The on-call's first move is always "which slice is failing," because that converts a vague outage into a specific subsystem.

#### Q123. [Behavioral] A major customer escalates that calls are "unreliable," but your dashboards look green. How do you handle it?

This tests how you reconcile conflicting signals and manage a high-stakes escalation without dismissing the customer or thrashing.

- **Take it seriously and avoid the "works on my dashboard" trap**: green aggregates can hide a cohort. The customer is a *specific slice* (their ISP, region, device fleet, browser version, network policy) that the overall numbers average away. Pull metrics filtered to **their** org/region/ASN, not the global view.
- **Get specifics fast**: exact times, participants, which direction failed, network type (corporate VPN? specific office?). Often it's a locked-down corporate network blocking UDP with no TURN-over-443, or a particular browser/extension — invisible globally, 100% reproducible for them.
- **Instrument the gap**: if existing telemetry can't see their problem, add targeted logging (candidate types, ICE state, `getStats`) for that customer and capture a real failing call. "We can't reproduce" is not an answer; "we instrumented and found you're falling back to TURN-TCP and our nearest PoP is 200ms away" is.
- **Communicate continuously**: acknowledge, give a concrete investigation plan and timeline, and share findings — escalations are as much about trust as the fix.
- **Fix systemically and close the loop**: deploy the fix (e.g. a closer PoP, `turns:443`), *and* add a dashboard slice/alert for that cohort so it's no longer invisible — then confirm with the customer using their own data.

What this signals: you respect customer-reported reality over comforting aggregates, you debug with data rather than defensiveness, and you turn each escalation into permanent observability so the class of problem can't hide again. The worst answer is "our metrics are fine, so it must be their network" — even if their network *is* the cause, providing the fallback and the proof is your job.

## ✅ Key Takeaways

- WebRTC gives you encrypted, sub-second P2P audio/video/data; **signaling is your responsibility** and runs out of band.
- NAT traversal is **ICE** orchestrating **STUN** (find your public address) and **TURN** (relay fallback); always deploy both, with `turns:` over 443 for restrictive networks.
- Negotiation is **SDP offer/answer**; encryption is **DTLS-SRTP** and mandatory; media prefers **UDP** to avoid head-of-line blocking.
- Topologies scale as **mesh (≤3–4) → SFU (default) → MCU (niche)**; SFUs stay cheap by forwarding, not transcoding.
- **Simulcast/SVC** plus per-receiver layer selection make SFUs adaptive; **GCC/TWCC** bandwidth estimation and the **jitter buffer** manage the latency-vs-quality trade-off.
- Scale large calls with **cascaded SFUs, active-speaker/last-N forwarding, and WHIP/WHEP** for ingest/egress; pick **LiveKit/mediasoup/Janus/Pion** by how much control vs. convenience you need.

## ⚠️ Common Pitfalls

- Forgetting `getUserMedia` requires a **secure context** (HTTPS/localhost) — it fails silently on plain HTTP.
- Shipping with **only STUN and no TURN** — ~10–20% of users behind symmetric NAT/firewalls will fail to connect.
- Using **static TURN credentials** instead of time-limited HMAC credentials — relay bandwidth abuse and security risk.
- Trying to scale **mesh** beyond a handful of participants — uplink and CPU collapse at O(N²).
- Calling `track.stop()` when you only meant to mute — use `track.enabled = false` to keep the connection warm.
- Not handling **glare/renegotiation** (skipping perfect negotiation) → broken state when both sides renegotiate.
- Leaking `VideoFrame` objects in Insertable Streams by forgetting `frame.close()` → GPU memory exhaustion and tab crashes.
- Assuming DTLS-SRTP means E2EE — in an **SFU the server sees plaintext** unless you add encoded-transform E2EE.
- Blocking on full ICE gathering instead of **trickle ICE** → multi-second call setup delays.

## 📚 Further Reading

- *High Performance Browser Networking* (Ilya Grigorik) — WebRTC chapter; the canonical primer on the transport stack.
- MDN WebRTC API documentation — `RTCPeerConnection`, `getUserMedia`, `RTCDataChannel`, perfect negotiation guide.
- IETF RFCs: 8825 (overview), 8445 (ICE), 8866 (SDP), 8838 (trickle ICE), 8834 (media transport/SRTP), 8829 (JSEP).
- webrtcHacks and webrtcforthecurious.com — deep, practical explainers on ICE, SFUs, simulcast, and bandwidth estimation.
- mediasoup, Janus, LiveKit, and Pion documentation — for hands-on SFU/server architecture.
- WHIP (RFC 9725) and WHEP (IETF draft) — standardized HTTP-based WebRTC ingest and egress.
