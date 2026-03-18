import { cn } from "../../lib/utils"

function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("animate-pulse rounded-xl neu-flat", className)}
      {...props}
    />
  )
}

export { Skeleton }
