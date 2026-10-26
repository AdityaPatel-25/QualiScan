import React from "react";

/**
 * Accessible inline or block loading spinner.
 *
 * @param {Object} props
 * @param {'sm'|'md'|'lg'} [props.size='md']
 * @param {string} [props.label='Loading...']
 * @param {boolean} [props.centered=false]
 */
export function Spinner({ size = "md", label = "Loading...", centered = false }) {
  const content = (
    <div
      className={`spinner-container spinner-${size}`}
      role="status"
      aria-label={label}
      data-testid="common-spinner"
    >
      <div className="spinner-circle"></div>
      {label && <span className="spinner-text">{label}</span>}
    </div>
  );

  if (centered) {
    return <div className="spinner-centered-wrapper">{content}</div>;
  }
  return content;
}
