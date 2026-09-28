/**
 * What an enrolment the page stopped waiting for amounts to (third review of UA2).
 *
 * Enrolling seeds one wrapper per source class and can outlive the page's deadline having
 * committed. The timeout cannot say which; only the model can, so the caller re-reads it and
 * passes how many of the dataset's classes are enrolled now. Enrolling again is not the answer
 * either way: while the first is still seeding, a second can collide with it on `model_source`'s
 * (model, dataset, class) key; once it has landed, `addSource` finds the rows and changes nothing.
 *
 * `enrolledNow` is the number of the dataset's model sources after the re-read, or `undefined`
 * when the re-read itself failed. `null` means it landed — the editor replaces the note.
 */
export type EnrolCheck =
  | { kind: 'not-yet'; message: string }
  | { kind: 'unknown'; message: string }

export function describeEnrolCheck(enrolledNow: number | undefined): EnrolCheck | null {
  if (enrolledNow === undefined) {
    return {
      kind: 'unknown',
      message: 'The page stopped waiting for this enrolment, and the model could not be read to check '
        + 'whether it landed. Check again rather than enrolling again — while the first is still '
        + 'seeding, a second can collide with it; once it has landed, enrolling again changes nothing.',
    }
  }
  if (enrolledNow > 0) return null
  return {
    kind: 'not-yet',
    message: 'Not enrolled yet — the server may still be seeding this dataset’s wrappers. Check again '
      + 'in a moment rather than enrolling again: while the first is still seeding, a second can '
      + 'collide with it. If it is still not enrolled after a few minutes, the server refused it: '
      + 'enroll again to see why.',
  }
}
