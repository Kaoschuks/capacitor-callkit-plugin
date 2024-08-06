export interface CallKitPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
