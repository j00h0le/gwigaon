// Prompt, output schema and validation for the spoken arrival notes (ADR 0021).
// The passenger's three notes are facts to keep word for word; the model only joins them into a polite request.

export interface ArrivalNotes {
  entrance: string;
  parking: string;
  key: string;
}

/** Same bound as the app's 설정 field (Settings.MAX_NOTE). */
export const MAX_NOTE = 30;

/** Two short sentences at most; a little room over the template's length. */
export const MAX_SENTENCE = 160;

export const PHRASE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["sentence"],
  properties: {
    sentence: {
      type: "string",
      description: "At most two polite Korean sentences to a designated driver, ending with 감사합니다.",
    },
  },
} as const;

export const SYSTEM_PROMPT = `You write what a car says aloud to a designated driver (대리기사) near the destination.
The passenger left up to three notes: 출입문 (which entrance to use), 주차 (where to park), 차 키 (what to do with the key).

Rules:
- Write at most two short, natural Korean sentences in a polite request register (해 주세요 / 부탁드립니다).
- Mention the notes in this order: 출입문, 주차, 차 키. Leave out a note that is empty.
- Copy every note's text exactly as written, character for character. Do not translate, shorten or reword a note.
- Add no facts, names, numbers, times or advice that are not in the notes.
- End with "감사합니다."
- Output only the JSON object.`;

/** The notes as the model sees them; empty ones are marked so it can leave them out. */
export function buildUserPrompt(notes: ArrivalNotes): string {
  const show = (v: string) => (v.trim() ? v.trim() : "(없음)");
  return [`출입문: ${show(notes.entrance)}`, `주차: ${show(notes.parking)}`, `차 키: ${show(notes.key)}`].join("\n");
}

export class InvalidNotesError extends Error {}
export class InvalidSentenceError extends Error {}

/** Request body → notes; throws InvalidNotesError on a wrong shape, an over-long note, or all empty. */
export function parseNotes(body: unknown): ArrivalNotes {
  const o = (body ?? {}) as Record<string, unknown>;
  const field = (name: string): string => {
    const v = o[name] ?? "";
    if (typeof v !== "string") throw new InvalidNotesError(`${name} is not a string`);
    // Spaces are collapsed like the sentence's (validateSentence), so a note typed with a double space can still
    // be found verbatim (Checkpoint F review R3).
    if (/[\r\n]/.test(v.trim())) throw new InvalidNotesError(`${name} has a line break`);
    const t = v.trim().replace(/\s+/g, " ");
    if (t.length > MAX_NOTE) throw new InvalidNotesError(`${name} is too long`);
    return t;
  };
  const notes = { entrance: field("entrance"), parking: field("parking"), key: field("key") };
  if (!notes.entrance && !notes.parking && !notes.key) throw new InvalidNotesError("no notes");
  return notes;
}

/**
 * Model output → the sentence. Throws InvalidSentenceError unless it is JSON of the schema, short enough, ends
 * with 감사합니다, and contains every non-empty note verbatim (nothing dropped or reworded).
 */
export function validateSentence(raw: string, notes: ArrivalNotes): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new InvalidSentenceError("model output is not JSON");
  }
  const s = (parsed as Record<string, unknown>)?.sentence;
  if (typeof s !== "string" || !s.trim()) throw new InvalidSentenceError("no sentence");
  const sentence = s.trim().replace(/\s+/g, " ");
  if (sentence.length > MAX_SENTENCE) throw new InvalidSentenceError("sentence too long");
  if (!/감사합니다[.!]?$/.test(sentence)) throw new InvalidSentenceError("does not end with 감사합니다");
  for (const [name, value] of Object.entries(notes)) {
    if (value && !sentence.includes(value)) throw new InvalidSentenceError(`${name} not kept verbatim`);
  }
  return sentence;
}
