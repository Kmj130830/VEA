# Android native renderer bridge (first porting slice)

This change adds a real Android ARM64 ELF shared library named libveavsm.so. It ports the VSM render callback's transport boundary, not the Yamaha AI inference implementation.

Evidence recovered from VSM.dll:
- The renderPartiallyAsync callback type is std::function<void(const unsigned int&, const double&, const unsigned __int64&, const float*, const float*)>.
- VSM exposes audio-buffer sample and sample-count accessors through its audio-buffer interfaces.
- Sequence sampling-rate accessors are exposed; the observed default context sample rate is 44,100 Hz.

Implemented in this slice:
- A stable C ABI describing the exact callback parameter types.
- A thread-safe capture object that copies the two float planes without assuming their semantic labels.
- A float32 IEEE WAV writer for offline validation and eventual callback hookup.
- Host-side tests for block collection, order preservation, sample-rate metadata, RIFF encoding, and interleaving.

Not implemented by this slice:
- The S5API inference/model execution path.
- The VSM sequence manager, observer dispatch, or Yamaha G2PA conversion.
- Loading .vtb2/.vpit/.vhnv/.vtmg into a compatible inference runtime.
- Exact interpretation of callback metadata and whether the two float planes represent left/right channels.

Do not treat the presence of libveavsm.so as proof that Megpoid V7 synthesis works. It is the first portable native boundary needed by the renderer port.
