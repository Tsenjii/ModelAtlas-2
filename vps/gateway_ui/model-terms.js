"use strict";

function gatewayPriceLabel(quote) {
  if (!quote) return "";
  if (quote.free) return "免費";
  const legs = quote.legs;
  const prefix = quote.basis === "NOMINAL" ? "標價 " : "";
  if (legs.unit === "USD/request") return prefix + "US$" + (legs.fixed ?? "0") + "／次";
  const tokens = [["輸入", legs.input], ["輸出", legs.output], ["快取", legs.cache]]
    .filter(([, value]) => value != null);
  const text = tokens.map(([label, value]) => label + " $" + value).join(" · ");
  const rate = text ? text + "／千 Token" : "";
  const fee = legs.fixed == null ? "" : "US$" + legs.fixed + "／次";
  return prefix + [rate, fee].filter(Boolean).join(" + ");
}

function gatewayDailyLabel(daily) {
  if (!daily) return "";
  return new Intl.NumberFormat("zh-TW").format(BigInt(daily.value)) + " 次／日";
}

function gatewayTermsLabel(terms) {
  if (!terms) return "";
  let price = terms.varied_price ? "查看價格" : gatewayPriceLabel(terms.price);
  let daily = gatewayDailyLabel(terms.daily);
  if (terms.connections && terms.price?.free) price = "";
  if (terms.connections > 1) {
    if (price && terms.price?.legs.unit === "USD/request") price = "最低" + (terms.price.basis === "NOMINAL" ? "" : " ") + price;
    if (daily) daily = "最高 " + daily;
  }
  return [price, daily].filter(Boolean).join(" · ");
}
