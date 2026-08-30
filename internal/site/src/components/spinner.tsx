import { t } from "@lingui/core/macro"
import { LoaderCircleIcon } from "lucide-react"
import { cn } from "@/lib/utils"

export default function ({ msg, className }: { msg?: string; className?: string }) {
	return (
		<div
			role="status"
			className={cn(className, "flex flex-col items-center justify-center gap-2.5 h-full absolute inset-0")}
		>
			<LoaderCircleIcon className="animate-spin size-6 text-primary/70" aria-hidden="true" />
			{msg ? (
				<p className="text-center text-sm px-4 text-muted-foreground">{msg}</p>
			) : (
				<span className="sr-only">{t`Loading`}</span>
			)}
		</div>
	)
}
