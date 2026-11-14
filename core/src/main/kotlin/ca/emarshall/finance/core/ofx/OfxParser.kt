package ca.emarshall.finance.core.ofx

import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.core.model.ParsedBalance
import ca.emarshall.finance.core.model.ParsedStatement
import ca.emarshall.finance.core.model.ParsedTransaction
import ca.emarshall.finance.core.money.Money
import java.time.LocalDate

/**
 * Reads OFX 1.x (SGML, unclosed leaf tags) and OFX 2.x (XML) bank and credit card statements.
 * QFX (Quicken) and QBO (QuickBooks) are OFX with an extra INTU.BID tag, so they go through here too.
 *
 * OFX amounts are already from the account holder's side: debits negative, credits positive, for both
 * bank accounts and credit cards. LEDGERBAL on a credit card is negative when money is owed.
 */
object OfxParser {

    class Node(val name: String) {
        val children = ArrayList<Node>()
        var text: String? = null
        fun child(n: String): Node? = children.firstOrNull { it.name == n }
        fun value(n: String): String? = child(n)?.text
        fun all(n: String): List<Node> = children.filter { it.name == n }
        fun find(n: String): Node? {
            if (name == n) return this
            for (c in children) c.find(n)?.let { return it }
            return null
        }
        fun findAll(n: String, out: MutableList<Node> = ArrayList()): List<Node> {
            if (name == n) out.add(this)
            children.forEach { it.findAll(n, out) }
            return out
        }
    }

    fun looksLikeOfx(text: String): Boolean {
        val head = text.take(4096).uppercase()
        return head.contains("OFXHEADER") || head.contains("<OFX>") || head.contains("<?OFX")
    }

    fun parse(text: String): ParsedStatement {
        val root = parseTree(text)
        val warnings = ArrayList<String>()

        val ccStmt = root.find("CCSTMTRS")
        val bankStmt = root.find("STMTRS")
        val stmt = ccStmt ?: bankStmt ?: throw IllegalArgumentException("No bank or credit card statement in this OFX file")
        val isCard = ccStmt != null

        val currency = stmt.value("CURDEF")
        val acct = if (isCard) stmt.find("CCACCTFROM") else stmt.find("BANKACCTFROM")
        val acctId = acct?.value("ACCTID")?.filter { it.isLetterOrDigit() }
        val acctTypeText = acct?.value("ACCTTYPE")?.uppercase()
        val type = when {
            isCard -> AccountType.CREDIT_CARD
            acctTypeText == "CHECKING" -> AccountType.CHEQUING
            acctTypeText == "SAVINGS" || acctTypeText == "MONEYMRKT" -> AccountType.SAVINGS
            acctTypeText == "CREDITLINE" -> AccountType.LINE_OF_CREDIT
            else -> null
        }

        val org = (root.find("FI")?.value("ORG") ?: "") + " " + (root.find("FI")?.value("FID") ?: "") + " " +
            (root.find("INTU.BID")?.text ?: "")
        val institution = detectInstitution(org + " " + text.take(2000))

        val tranList = stmt.find("BANKTRANLIST")
        val seenFitIds = HashSet<String>()
        val txs = ArrayList<ParsedTransaction>()
        tranList?.all("STMTTRN")?.forEach { t ->
            val date = parseDate(t.value("DTPOSTED")) ?: parseDate(t.value("DTUSER"))
            val amount = runCatching { Money.parse(t.value("TRNAMT")) }.getOrNull()
            if (date == null || amount == null) {
                warnings.add("Skipped a transaction without a date or amount (FITID ${t.value("FITID")})")
                return@forEach
            }
            val fitId = t.value("FITID")?.trim()?.takeIf { it.isNotEmpty() }
            // Some Quicken exports repeat rows with the same FITID; keep the first.
            if (fitId != null && !seenFitIds.add(fitId)) {
                warnings.add("Dropped a repeated row in the file (FITID $fitId)")
                return@forEach
            }
            val name = t.value("NAME")?.trim().orEmpty()
            val memo = t.value("MEMO")?.trim().orEmpty()
            val desc = when {
                name.isEmpty() -> memo
                memo.isEmpty() || name.contains(memo, ignoreCase = true) -> name
                else -> "$name $memo"
            }
            txs.add(ParsedTransaction(date, amount, desc, fitId = fitId, currency = t.child("CURRENCY")?.value("CURSYM") ?: currency))
        }

        val balances = ArrayList<ParsedBalance>()
        stmt.find("LEDGERBAL")?.let { lb ->
            val d = parseDate(lb.value("DTASOF"))
            val b = runCatching { Money.parse(lb.value("BALAMT")) }.getOrNull()
            if (d != null && b != null) balances.add(ParsedBalance(d, b))
        }

        return ParsedStatement(
            format = FileFormat.OFX,
            transactions = txs,
            balances = balances,
            hint = AccountHint(institution, type, acctId?.takeLast(4), currency),
            periodStart = parseDate(tranList?.value("DTSTART")),
            periodEnd = parseDate(tranList?.value("DTEND")),
            warnings = warnings,
        )
    }

    fun detectInstitution(text: String): Institution? {
        val t = text.uppercase()
        return when {
            t.contains("SCOTIA") || t.contains("BNS") -> Institution.SCOTIABANK
            t.contains("CANADIAN TIRE") || t.contains("TRIANGLE") || t.contains("CTFS") -> Institution.CANADIAN_TIRE
            else -> null
        }
    }

    /** OFX dates are `YYYYMMDD[HHMMSS[.XXX]][[-5:EST]]`. The calendar date is taken as written. */
    fun parseDate(s: String?): LocalDate? {
        val d = s?.trim()?.takeIf { it.length >= 8 }?.substring(0, 8) ?: return null
        if (!d.all { it.isDigit() }) return null
        return runCatching { LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt()) }.getOrNull()
    }

    /**
     * Builds an element tree that works for both SGML and XML. A tag followed by text and no matching
     * close tag (SGML leaf) gets that text and is closed implicitly at the next tag.
     */
    fun parseTree(text: String): Node {
        val start = text.indexOf("<OFX", ignoreCase = true)
        require(start >= 0) { "Not an OFX file" }
        val body = text.substring(start)
        val root = Node("#root")
        val stack = ArrayDeque<Node>().apply { addLast(root) }
        val tag = Regex("<(/?)([A-Za-z0-9_.]+)[^>]*>")
        var pos = 0
        var pendingLeaf: Node? = null
        for (m in tag.findAll(body)) {
            val between = body.substring(pos, m.range.first)
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].uppercase()
            val txt = decode(between.trim())
            if (txt.isNotEmpty()) {
                stack.last().text = txt
                pendingLeaf = stack.last()
            }
            if (closing) {
                // Pop up to and including the matching element; unclosed SGML leaves are popped silently.
                if (stack.any { it.name == name }) {
                    while (stack.size > 1) {
                        val n = stack.removeLast()
                        if (n.name == name) break
                    }
                }
                pendingLeaf = null
            } else {
                // An open tag after a leaf's text means that leaf was never closed (SGML).
                if (pendingLeaf != null && stack.last() === pendingLeaf) stack.removeLast()
                pendingLeaf = null
                val node = Node(name)
                stack.last().children.add(node)
                if (!m.value.endsWith("/>")) stack.addLast(node)
            }
        }
        return root
    }

    private fun decode(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
}
