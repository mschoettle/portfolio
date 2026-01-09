package name.abuchen.portfolio.datatransfer.pdf;

import java.util.Locale;

import name.abuchen.portfolio.datatransfer.ExtractorUtils;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class QuestradePDFExtractor extends AbstractPDFExtractor
{
    public QuestradePDFExtractor(Client client)
    {
        super(client);

        addBankIdentifier("Questrade, Inc.");

        addAccountStatementTransaction();
        addBuySellTransaction();
        addDividendTransaction();
    }

    @Override
    public String getLabel()
    {
        return "Questrade, Inc.";
    }

    private void addAccountStatementTransaction()
    {
        final var type = new DocumentType("04\\. ACTIVITY DETAILS", 
                        documentContext -> documentContext.
                                        section("currency") //
                                        .match(".*Combined in (?<currency>[\\w]{3})$") //
                                        .assign((ctx, v) -> ctx.put("currency", asCurrencyCode(v.get("currency")))));
        this.addDocumentTyp(type);

        var pdfTransaction = new Transaction<AccountTransaction>();
        var firstRelevantLine = new Block(".* Contribution .*");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction

            .subject(() -> {
                var accountTransaction = new AccountTransaction();
                accountTransaction.setType(AccountTransaction.Type.DEPOSIT);
                return accountTransaction;
            })

            // @formatter:off
            // Matches lines like:
            // 04-09-2025 04-09-2025 Contribution CONT 6263984218 - - - - 10,000.00 - - - -
            // @formatter:on
            .section("date", "amount")
            .documentContext("currency")
            .match("^(?<date>[\\d]{2}\\-[\\d]{2}\\-[\\d]{4}) [\\d]{2}\\-[\\d]{2}\\-[\\d]{4} Contribution .* (?<amount>[\\.,\\d]+).*$")
            .assign((t, v) -> {
                // date format is mm-dd-yyyy
                t.setDateTime(asDate(v.get("date"), Locale.US));
                t.setAmount(asAmount(v.get("amount")));
                t.setCurrencyCode(v.get("currency"));
                t.setNote("Contribution");
            })
            
            .wrap(TransactionItem::new);
    }

    private void addBuySellTransaction()
    {
        final var type = new DocumentType("04\\. ACTIVITY DETAILS",
                        documentContext -> documentContext.
                                        section("currency") //
                                        .match(".*Combined in (?<currency>[\\w]{3})$") //
                                        .assign((ctx, v) -> ctx.put("currency", asCurrencyCode(v.get("currency")))));
        this.addDocumentTyp(type);

        var pdfTransaction = new Transaction<BuySellEntry>();
        var firstRelevantLine = new Block(".* Buy .*");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
        
            .subject(() -> {
                var tx = new BuySellEntry();
                tx.setType(PortfolioTransaction.Type.BUY);
                return tx;
            })

            // @formatter:off
            // Matches lines like:
            // 04-10-2025 04-11-2025 Buy .VEQT VANGUARD ALL-EQUITY ETF  PORTFOLIO
            // 01-16-2023 01-18-2023 Buy .VEQT VANGUARD ALL-EQUITY ETF|PORTFOLIO ETF
            // 01-17-2023 01-19-2023 Buy .XEQT UNITS|WE ACTED AS AGENT|AVG PRICE - ASK 19 25.320 (481.08) - (481.08) - - - -
            // @formatter:on
            .section("date")
            .match("^(?<date>[\\d]{2}\\-[\\d]{2}\\-[\\d]{4}) [\\d]{2}\\-[\\d]{2}\\-[\\d]{4} Buy .*")
            .assign((t, v) -> {
                t.setDate(asDate(v.get("date"), Locale.US));
            })

            .oneOf(
                // @formatter:off
                // Matches lines like:
                // 01-17-2023 01-19-2023 Buy .XEQT UNITS|WE ACTED AS AGENT|AVG PRICE - ASK 19 25.320 (481.08) - (481.08) - - - -
                // @formatter:on
                section -> section
                    .attributes( "tickerSymbol")
                    .match("^.* Buy \\.(?<tickerSymbol>\\S+) UNITS\\|WE ACTED AS AGENT\\|AVG PRICE - ASK .*$")
                    .documentContext("currency")
                    .assign((t, v) -> {
                        // The security name cannot be null
                        v.put("name", "");
                        v.put("tickerSymbol", asTickerSymbol(v.get("tickerSymbol")));

                        t.setSecurity(getOrCreateSecurity(v));
                        t.setCurrencyCode(v.get("currency"));
                    }),

                // @formatter:off
                // Matches lines like:
                // 04-10-2025 04-11-2025 Buy .VEQT VANGUARD ALL-EQUITY ETF  PORTFOLIO
                // 01-16-2023 01-18-2023 Buy .VEQT VANGUARD ALL-EQUITY ETF|PORTFOLIO ETF
                // @formatter:on
                section -> section
                    .attributes( "tickerSymbol", "name")
                    .match("^.* Buy \\.(?<tickerSymbol>\\S+) (?<name>.*?)$")
                    .documentContext("currency")
                    .assign((t, v) -> {
                        v.put("name", v.get("name").trim());
                        v.put("tickerSymbol", asTickerSymbol(v.get("tickerSymbol")));

                        t.setSecurity(getOrCreateSecurity(v));
                        t.setCurrencyCode(v.get("currency"));
                    })
            )

            .oneOf(

                // @formatter:off
                // Matches lines like:
                // ETF UNIT  WE ACTED AS AGENT 50.0000 40.930 (2,046.50) - (2,046.50) - - - -
                // UNITS  WE ACTED AS AGENT 100 25.920 (2,592.00) - (2,592.00) - - - -
                // @formatter:on
                section -> section
                    .attributes("shares", "gross", "amount")
                    .match("^.*\\s?UNITS?\\s+WE ACTED AS AGENT (?<shares>[\\d\\.,]+) (?<price>[\\d\\.,]+) \\((?<gross>[\\d,\\.\\-]+)\\) - \\((?<amount>[\\d,\\.\\-]+)\\) .*$")
                    .assign((t, v) -> {
                        t.setShares(asShares(v.get("shares"), "en", "CA"));
                        t.setAmount(asAmount(v.get("amount")));
                    }),

                // @formatter:off
                // Matches lines like:
                // UNIT|WE ACTED AS AGENT 50.0000 40.930 (2,046.50) (0.10) (2,046.60) - - - -
                // UNITS  WE ACTED AS AGENT 76 25.960 (1,972.96) (0.27) (1,973.23) - - - -
                // @formatter:on
                section -> section
                    .attributes("shares", "gross", "fee", "amount")
                    .documentContext("currency")
                    .match("^UNITS?(\\||  )WE ACTED AS AGENT (?<shares>[\\d\\.,]+) (?<price>[\\d\\.,]+) \\((?<gross>[\\d,\\.\\-]+)\\) \\((?<fee>[\\d,\\.\\-]+)\\) \\((?<amount>[\\d,\\.\\-]+)\\) .*$")
                    .assign((t, v) -> {
                        t.setShares(asShares(v.get("shares"), "en", "CA"));
                        t.setAmount(asAmount(v.get("amount")));

                        processFeeEntries(t, v, type);
                    }),

                // @formatter:off
                // Matches lines like:
                // 01-17-2023 01-19-2023 Buy .XEQT UNITS|WE ACTED AS AGENT|AVG PRICE - ASK 19 25.320 (481.08) - (481.08) - - - -
                // @formatter:on
                section -> section
                    .attributes("shares", "gross", "amount")
                    .match("^.+ UNITS\\|WE ACTED AS AGENT\\|AVG PRICE - ASK (?<shares>[\\d\\.,]+) (?<price>[\\d\\.,]+) \\((?<gross>[\\d,\\.\\-]+)\\) - \\((?<amount>[\\d,\\.\\-]+)\\) .*$")
                    .assign((t, v) -> {
                        t.setShares(asShares(v.get("shares"), "en", "CA"));
                        t.setAmount(asAmount(v.get("amount")));
                    })
            )

            .wrap(BuySellEntryItem::new);
    }

    private void addDividendTransaction()
    {
        final var type = new DocumentType("04\\. ACTIVITY DETAILS",
                        documentContext -> documentContext.
                                        section("currency") //
                                        .match(".*Combined in (?<currency>[\\w]{3})$") //
                                        .assign((ctx, v) -> ctx.put("currency", asCurrencyCode(v.get("currency")))));
        this.addDocumentTyp(type);

        var pdfTransaction = new Transaction<AccountTransaction>();
        var firstRelevantLine = new Block(".* UNITS?( |\\|)DIST .*");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
            .subject(() -> {
                var accountTransaction = new AccountTransaction();
                accountTransaction.setType(AccountTransaction.Type.DIVIDENDS);
                return accountTransaction;
            })

            .section("date", "tickerSymbol", "shares", "amount", "recNote")
            .documentContext("currency")

            // @formatter:off
            // Matches lines like:
            // 01-07-2025 01-07-2025    .VEQT UNIT DIST      ON      29 SHS REC 12/30/24 PAY - - - - 20.69 - - - -
            // 09-29-2023 09-29-2023    .XEQT UNITS DIST      ON     95 SHS REC 09/26/23 - - - - 23.55 - - - -
            // 03-31-2023 03-31-2023    .XEQT UNITS|DIST      ON     19 SHS|REC 03/23/23 - - - - 1.67 - - - -
            // @formatter:on
            .match("^(?<date>\\d{2}-\\d{2}-\\d{4}) \\d{2}-\\d{2}-\\d{4}\\s+\\.(?<tickerSymbol>\\S+) UNITS?( |\\|)DIST\\s+ON\\s+(?<shares>[\\d,\\.]+) SHS( |\\|)(?<recNote>REC \\d{2}/\\d{2}/\\d{2})( PAY)? (\\- ){4}(?<amount>[\\d,\\.]+).*$")
            .assign((t, v) -> {
                v.put("tickerSymbol", asTickerSymbol(v.get("tickerSymbol")));

                t.setDateTime(asDate(v.get("date"), Locale.US));
                t.setCurrencyCode(v.get("currency"));
                t.setShares(asShares(v.get("shares"), "en", "CA"));
                t.setAmount(asAmount(v.get("amount")));
                t.setNote(v.get("recNote"));
                t.setSecurity(getOrCreateSecurity(v));
            })

            .wrap(TransactionItem::new);
    }

    @Override
    protected long asAmount(String value)
    {
        return ExtractorUtils.convertToNumberLong(value, Values.Amount, "en", "CA");
    }

    private String asTickerSymbol(String value)
    {
        // If the exchange designator is missing, assume Toronto Stock Exchange (.TO)
        if (!value.contains("."))
        {
            value = value.trim() + ".TO";
        }
        return value;
    }
}
