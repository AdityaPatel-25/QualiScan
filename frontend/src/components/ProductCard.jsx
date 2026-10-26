import React from "react";

/**
 * Product-specific quality breakdown card (cap, card, biscuit).
 *
 * @param {Object} props
 * @param {string} props.productType
 * @param {number} props.total
 * @param {number} props.pass
 * @param {number} props.reject
 * @param {number} props.defectRate
 */
export function ProductCard({
  productType = "unknown",
  total = 0,
  pass = 0,
  reject = 0,
  defectRate = 0,
}) {
  const ratePct = (defectRate * 100).toFixed(1);
  const passYield = total > 0 ? ((pass / (pass + reject || 1)) * 100).toFixed(1) : "100.0";

  const getProductIcon = (type) => {
    switch (type.toLowerCase()) {
      case "cap":
        return "🥫";
      case "card":
        return "💳";
      case "biscuit":
        return "🍪";
      default:
        return "📦";
    }
  };

  return (
    <div className="product-card" data-testid={`product-card-${productType}`}>
      <div className="product-card-header">
        <span className="product-icon">{getProductIcon(productType)}</span>
        <div className="product-meta">
          <h4 className="product-name">{productType.toUpperCase()}</h4>
          <span className="product-yield">Yield: {passYield}%</span>
        </div>
      </div>

      <div className="product-metrics-grid">
        <div className="product-metric">
          <span className="metric-label">TOTAL</span>
          <span className="metric-val">{total}</span>
        </div>
        <div className="product-metric">
          <span className="metric-label text-success">PASS</span>
          <span className="metric-val text-success">{pass}</span>
        </div>
        <div className="product-metric">
          <span className="metric-label text-danger">REJECT</span>
          <span className="metric-val text-danger">{reject}</span>
        </div>
        <div className="product-metric">
          <span className="metric-label">DEFECT RATE</span>
          <span className={`metric-val ${defectRate > 0.08 ? "text-danger" : ""}`}>
            {ratePct}%
          </span>
        </div>
      </div>
    </div>
  );
}
