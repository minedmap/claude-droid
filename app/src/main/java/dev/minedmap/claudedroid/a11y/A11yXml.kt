package dev.minedmap.claudedroid.a11y

/** Platform-free snapshot of one accessibility node (so the XML writer is unit-testable on the JVM). */
class A11yNode(
  val cls: String, val pkg: String, val text: String, val desc: String, val id: String,
  val left: Int, val top: Int, val right: Int, val bottom: Int,
  val clickable: Boolean = false, val longClickable: Boolean = false, val scrollable: Boolean = false,
  val checkable: Boolean = false, val checked: Boolean = false, val focusable: Boolean = false,
  val focused: Boolean = false, val selected: Boolean = false, val password: Boolean = false,
  val enabled: Boolean = true,
  val children: List<A11yNode> = emptyList(),
)

/** Writes the same XML shape as `uiautomator dump`, so control-mcp.mjs parses it unchanged. */
object A11yXml {
  fun esc(s: String): String {
    val b = StringBuilder(s.length + 8)
    for (c in s) when {
      c == '&' -> b.append("&amp;"); c == '<' -> b.append("&lt;"); c == '>' -> b.append("&gt;")
      c == '"' -> b.append("&quot;")
      c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> {}   // not representable in XML 1.0
      else -> b.append(c)
    }
    return b.toString()
  }

  fun write(roots: List<A11yNode>, rotation: Int = 0): String {
    val b = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"$rotation\">")
    var n = 0
    fun node(x: A11yNode) {
      b.append("<node index=\"${n++}\" text=\"${esc(x.text)}\" resource-id=\"${esc(x.id)}\" class=\"${esc(x.cls)}\" package=\"${esc(x.pkg)}\"")
        .append(" content-desc=\"${esc(x.desc)}\" checkable=\"${x.checkable}\" checked=\"${x.checked}\" clickable=\"${x.clickable}\"")
        .append(" enabled=\"${x.enabled}\" focusable=\"${x.focusable}\" focused=\"${x.focused}\" scrollable=\"${x.scrollable}\"")
        .append(" long-clickable=\"${x.longClickable}\" password=\"${x.password}\" selected=\"${x.selected}\"")
        .append(" bounds=\"[${x.left},${x.top}][${x.right},${x.bottom}]\"")
      if (x.children.isEmpty()) b.append(" />") else { b.append(">"); x.children.forEach(::node); b.append("</node>") }
    }
    roots.forEach(::node)
    return b.append("</hierarchy>").toString()
  }
}
