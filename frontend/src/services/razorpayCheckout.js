/* ============================================================
   FlashReserve — Razorpay Checkout browser utility
   Loads the official Razorpay Checkout SDK on demand (once) and
   opens the TEST MODE checkout for a server-created order. This
   module is the only place that knows about the provider SDK;
   everything else works with plain values. The amount is never
   set here: with `order_id` Checkout takes it from the order the
   backend created, so no money value exists in frontend state.
   ============================================================ */

const CHECKOUT_SCRIPT_SRC = 'https://checkout.razorpay.com/v1/checkout.js'

/** Thrown when the checkout closed without a completed payment
    (user dismissed it, or the payment itself failed in-provider).
    The backend hold stays PENDING in both cases. */
export class CheckoutClosedError extends Error {
  constructor(message) {
    super(message)
    this.name = 'CheckoutClosedError'
  }
}

let checkoutScriptPromise = null

function loadCheckoutScript() {
  if (window.Razorpay) return Promise.resolve()
  if (checkoutScriptPromise) return checkoutScriptPromise

  checkoutScriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = CHECKOUT_SCRIPT_SRC
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      // Allow a later attempt; this one simply failed.
      checkoutScriptPromise = null
      script.remove()
      reject(new Error('Razorpay Checkout script failed to load'))
    }
    document.head.appendChild(script)
  })
  return checkoutScriptPromise
}

/**
 * Opens Razorpay Checkout for a backend-created order and resolves
 * with the values the verification endpoint requires — already
 * mapped from Razorpay's snake_case handler response to the
 * backend's camelCase DTO fields. Rejects with CheckoutClosedError
 * when the checkout closes without a completed payment.
 */
export function openRazorpayCheckout({ keyId, orderId, description, prefillEmail }) {
  return loadCheckoutScript().then(
    () =>
      new Promise((resolve, reject) => {
        let settled = false

        const checkout = new window.Razorpay({
          key: keyId,
          order_id: orderId,
          name: 'FlashReserve',
          description,
          theme: { color: '#7a5af8' },
          prefill: prefillEmail ? { email: prefillEmail } : undefined,
          handler: (response) => {
            settled = true
            resolve({
              razorpayOrderId: response.razorpay_order_id,
              razorpayPaymentId: response.razorpay_payment_id,
              razorpaySignature: response.razorpay_signature,
            })
          },
          modal: {
            ondismiss: () => {
              if (!settled) reject(new CheckoutClosedError('Checkout closed'))
            },
          },
        })

        // In-provider payment failure shows Razorpay's own error UI
        // and then dismisses; treat it like any closed checkout so
        // the booking simply stays on hold.
        if (typeof checkout.on === 'function') {
          checkout.on('payment.failed', () => {
            if (!settled) reject(new CheckoutClosedError('Payment failed'))
          })
        }

        checkout.open()
      }),
  )
}
