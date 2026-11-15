# joe-ng — backlog

Open work items, highest value first. When one is taken, its result goes in `STATUS.md` as an increment card and
the item leaves this list; new findings that are not fixed on the spot come here.

## WiFi

- **Key install stalls on slow boots (2026-11-14).** On some boots the firmware leaves ioctls unanswered for ~7 s
  after link-up. Both `WLC_SET_KEY`s then time out at 1.5 s each (~3 s per join; the keys do take effect), and
  the first DHCP discover went unanswered too. Measure with serial and the `/net` "WiFi join" rows. The join
  retry (`Cyw43.associate`, 3 attempts) has never been exercised on the Pi.
- **Firmware went silent at `WLC_SET_SSID` (2026-11-14, low priority).** Reading the MAC and `wpaie` just before
  `WLC_SET_SSID` (after `WLC_UP`) left that ioctl unanswered and no join event for 15 s. Not the tx credit window
  (txSeq 19, txMax 59 measured). Nothing reads there now; unexplained.

## Web server

- **A `/run` stop cannot reach a thread blocked in a JDK call that ignores interrupts (2026-11-14):** the forced
  stage acts at the program's own loop headers (Baseline stop polls), so a thread parked in, say, a socket read
  stops only when the call returns. While such a stop is PENDING, every poll in every run calls into the VM (~30-38
  cycles per loop pass on the Pi, against 1-2 with none pending): a per-task flag the poll could read inline would
  remove that, if a run left blocked like this ever matters.
- **A `/run` thread other than main that calls `System.exit` ends only itself (2026-11-14):** main carries on to its
  own end; the run reports the exit. There is no safe way to stop main from another thread.

## Collector

- **NEXT: a stop-the-world times out on QEMU whenever a collection lands in a busy loop (2026-11-15).**
  SecurityStockProbe now fails ALONE every time (was batch-only): `GC: STW TIMEOUT -- unparked core N task 4 ...
  (collection SKIPPED)`, the task being SeedGenerator's thread, whose entropy loop spins without a switch point --
  and QEMU gives launched programs no timer ticks, so that core never reaches the scheduler to park. The probe's own
  output is unchanged. Bisected: `db6b8cf` (#529) 11 collections, 0 timeouts, passes; `69ef9bc` (#530, unboxed
  increment 0) 12 collections, 1 timeout, fails -- the extra collection is what lands in the loop. WHY increment 0
  adds a collection is not measured (a guess: the allocator's changed register allocation moves what the
  conservative stack scan retains). Fix the timeout itself: a collection must be able to stop a core running a loop.

- **Cores 1-3's arenas are never collected (2026-11-14).** They are root-scanned whole: pauses grow from 0.5 to
  2.4 s over a session (root scan 92 -> 972 ms), they grow ~12.7 MB per boot upload, and they halt with "heap OOM"
  at 64 MB. A long pause can outlast a socket read timeout (`507 ... Read timed out`). The biggest remaining
  upload limit. It is also the WHOLE cause of the late ACKs: every ACK that waited >= 100 ms in the transmit
  queue spanned a collection (36 of 36 over four uploads; `/net` "WiFi tx waits >= 100 ms across a collection"),
  and those waits pass the sender's retransmit timeout (`TcpExt DupData` 1-49 per upload). The collection attempt
  (a second bitmap at `0x1E00_0000`, per-core sweeps) passed QEMU but hung the Pi twice and was dropped -- QEMU
  gives launched programs no timer ticks, so it cannot validate this; every trial needs serial.
- **41.5 MB live in the large-object region during an upload (2026-11-14).** Of ~60 MB live; the trace takes a
  steady ~413 ms. Unexplained -- measure what holds it before changing anything.
