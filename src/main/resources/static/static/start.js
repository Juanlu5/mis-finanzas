// Runs last, once app.js and every views/*.js are loaded: opens the view in the URL and fills the menu badges.
showView(viewFromHash());
refreshPriceBadge();
refreshBankExpiry();
refreshEconomyBadge();
