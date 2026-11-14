# Inline declaration changes

These cases edit only a private inline declaration. The public caller stays outside the diff. Java `Ab.enabled("feature")` supplies the fold selector, so each case compiles a mixed Java/Kotlin application.

| Declaration | Surviving copied body | Copy only in folded branch | No caller |
| --- | --- | --- | --- |
| Private member function | `kotlin_inline_member_uncontrolled` | `kotlin_inline_member_controlled` | `kotlin_inline_member_dead` |
| Private file-facade function | `kotlin_inline_top_level_uncontrolled` | `kotlin_inline_top_level_controlled` | `kotlin_inline_top_level_dead` |
| Private function in an object | `kotlin_inline_object_member_uncontrolled` | `kotlin_inline_object_member_controlled` | `kotlin_inline_object_member_dead` |
| Private file-facade function called from an object | `kotlin_inline_object_top_level_uncontrolled` | `kotlin_inline_object_top_level_controlled` | `kotlin_inline_object_top_level_dead` |
| Inline property | `kotlin_inline_property_uncontrolled` | `kotlin_inline_property_controlled` | `kotlin_inline_property_dead` |
| Property with inline accessor | `kotlin_inline_accessor_uncontrolled` | `kotlin_inline_accessor_controlled` | `kotlin_inline_accessor_dead` |
| Property with inline setter | `kotlin_inline_setter_uncontrolled` | `kotlin_inline_setter_controlled` | `kotlin_inline_setter_dead` |

The first column must fail with findings located on the changed declaration, even though the surviving instruction difference is inside the unchanged caller. The folded-branch column must prove control coverage using static evidence of the copied body. The no-caller column must fail: an uncalled production declaration has no control coverage. Missing invocation edges are insufficient evidence of control-induced removal for an inline declaration.

The function cases regress the missing-call-edge false pass. Private inline properties already failed through the baseline class-level obligation; the property and accessor cases preserve that detection and verify controlled and uncalled outcomes.

Inline obligations compare application methods containing copied bodies and retain the changed declaration unless same-input selected-control evidence establishes its removal. This is conservative: unrelated surviving changes can be attributed to more than one inline obligation. Precise copy-to-definition provenance could reduce this over-attribution, but is not required to detect the surviving copied body. The corpus is a correctness gate, not performance evidence.
