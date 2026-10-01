@include default
@include sdk

## 9. The public surface of this SDK

One question decides: does an existing caller stop compiling, stop linking, or
see a call that works today against the current API fail or behave
differently? If not, the change is additive and is not breaking.

The surface is every `public` and `protected` type, method, constructor and
field under `src/main/java`. The artifact is published to Maven Central as
`app.marketdata`, and it is idiomatic from both Java and Kotlin, so a change
must hold for both callers.

Java breaks in two ways, and they are not the same. Say which one you found:

- **source compatibility**: existing caller code stops compiling
- **binary compatibility**: existing compiled callers stop linking, and fail at
  runtime with `NoSuchMethodError` even though the source would compile

Breaking, for this SDK:

- a `public` or `protected` type, method, constructor or field removed or
  renamed
- a parameter type or a return type changed. Changing `dte(String)` to
  `dte(DteFilter)` breaks every caller, even when the API change behind it was
  purely additive
- a parameter type widened in place, which keeps source compatibility and
  breaks binary compatibility. Adding an overload keeps both
- an abstract method added to an interface without a `default` implementation,
  or added to an abstract class without a concrete one
- visibility narrowed, a method made `final`, or a class made `final`
- a checked exception added to a `throws` clause
- an enum constant removed, or reordered when callers depend on `ordinal()`

- a return type changed at all, widened included. The return type is part of
  the method descriptor, so existing compiled callers stop linking
- a change in behaviour that makes a call that works today against the current
  API fail, or return something different: a default value changed, a field
  dropped from a response model, a value the API accepts that the SDK now
  rejects

Not breaking:

- a new overload, a new `default` interface method, a new type, a new enum
  constant at the end of the list
- a new permitted subtype of a `sealed` interface or class, which is the same
  case as a new enum constant: an exhaustive `switch` or `when` gains a case to
  handle. List the new type as added; the sealed parent is not changed
- a client-side check on a new method or type: there is no existing call to
  break
- a new client-side check on an existing method that only rejects values the
  current API already rejects, only if it fails with the error type the API's
  rejection already produces (`BadRequestError`). Failing with any other type,
  such as `IllegalArgumentException`, changes the error a working caller
  handles, and is breaking

The version comes from the `sdkVersion` Gradle property at release time, not
from a file in the tree. The bump is therefore declared in `CHANGELOG.md` and in
the pull request description.
