const FRAME_MS = 20;
const START_RMS = 0.006;
const START_FRAMES = 2;
const HANG_FRAMES = 25;
const PREROLL_FRAMES = 10;
const BATCH_FRAMES = 5;
const MAX_FRAMES = 1500;

class VoiceTap extends AudioWorkletProcessor {
    constructor() {
        super();
        this.size = Math.round(sampleRate * FRAME_MS / 1000);
        this.frame = new Float32Array(this.size);
        this.fill = 0;
        this.frameStart = 0;
        this.preroll = [];
        this.batch = [];
        this.speaking = false;
        this.loud = 0;
        this.quiet = 0;
        this.length = 0;
        this.stopped = false;
        this.port.onmessage = e => {
            if (e.data !== 'stop' || this.stopped) return;
            this.end();
            this.stopped = true;
            this.port.postMessage({type: 'stopped'});
        };
    }

    process(inputs) {
        if (this.stopped) return false;
        const channel = inputs[0] && inputs[0][0];
        const n = channel ? channel.length : 128;
        for (let i = 0; i < n; i++) {
            if (this.fill === 0) this.frameStart = currentFrame + i;
            this.frame[this.fill++] = channel ? channel[i] : 0;
            if (this.fill === this.size) {
                this.onFrame(this.frame, this.frameStart);
                this.frame = new Float32Array(this.size);
                this.fill = 0;
            }
        }
        return true;
    }

    onFrame(frame, start) {
        let sum = 0;
        for (let i = 0; i < frame.length; i++) sum += frame[i] * frame[i];
        const loud = Math.sqrt(sum / frame.length) >= START_RMS;

        if (!this.speaking) {
            this.preroll.push({frame, start});
            if (this.preroll.length > PREROLL_FRAMES) this.preroll.shift();
            this.loud = loud ? this.loud + 1 : 0;
            if (this.loud >= START_FRAMES) this.begin();
            return;
        }
        this.push(frame);
        this.quiet = loud ? 0 : this.quiet + 1;
        if (this.quiet >= HANG_FRAMES || this.length >= MAX_FRAMES) this.end();
    }

    begin() {
        this.speaking = true;
        this.loud = 0;
        this.quiet = 0;
        this.length = 0;
        this.port.postMessage({type: 'start', time: this.preroll[0].start / sampleRate});
        for (const p of this.preroll) this.push(p.frame);
        this.preroll = [];
    }

    push(frame) {
        this.batch.push(frame);
        this.length++;
        if (this.batch.length >= BATCH_FRAMES) this.flushBatch();
    }

    flushBatch() {
        if (this.batch.length === 0) return;
        const samples = new Float32Array(this.batch.length * this.size);
        this.batch.forEach((f, i) => samples.set(f, i * this.size));
        this.batch = [];
        this.port.postMessage({type: 'audio', samples}, [samples.buffer]);
    }

    end() {
        if (!this.speaking) return;
        this.flushBatch();
        this.speaking = false;
        this.port.postMessage({type: 'end'});
    }
}

registerProcessor('lpc-voice-tap', VoiceTap);
