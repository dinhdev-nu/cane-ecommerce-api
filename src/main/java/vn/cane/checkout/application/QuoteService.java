package vn.cane.checkout.application;

import org.springframework.stereotype.Service;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.admin.application.SettingsReader;
import vn.cane.catalog.application.CheckoutCatalog;
import vn.cane.checkout.domain.CheckoutInput;
import vn.cane.checkout.domain.Quote;
import vn.cane.support.transaction.Transactions;

@Service
public final class QuoteService {
    private final Transactions transactions;
    private final SettingsReader settings;
    private final CheckoutCatalog catalog;
    private final CheckoutPolicy policy;

    public QuoteService(
            Transactions transactions, SettingsReader settings, CheckoutCatalog catalog, CheckoutPolicy policy) {
        this.transactions = transactions;
        this.settings = settings;
        this.catalog = catalog;
        this.policy = policy;
    }

    public Quote quote(CheckoutInput input) {
        return transactions.readSnapshot(
                () -> policy.evaluate(input, CheckoutSettings.from(settings.read()), catalog.read(input.skus())));
    }
}
